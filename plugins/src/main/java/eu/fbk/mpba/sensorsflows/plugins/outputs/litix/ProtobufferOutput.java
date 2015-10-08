package eu.fbk.mpba.sensorsflows.plugins.outputs.litix;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import eu.fbk.mpba.sensorsflows.OutputPlugin;
import eu.fbk.mpba.sensorsflows.base.ISensor;
import eu.fbk.mpba.sensorsflows.base.SensorDataEntry;
import eu.fbk.mpba.sensorsflows.base.SensorEventEntry;
import eu.fbk.mpba.sensorsflows.plugins.outputs.litix.Litix.SensorInfo;

public class ProtobufferOutput implements OutputPlugin<Long, double[]> {

    private class Queries {
        final static String i1 =
                "create table if not exists split (\n" +
                        " first_ts INTEGER PRIMARY KEY,\n" +
                        " track_id INTEGER,\n" +
                        " data BLOB NOT NULL,\n" +
                        " foreign key (track_id) references track(started_ts)\n" +
                        ");";
        final static String i2 =
                "create table if not exists track (\n" +
                        " started_ts INTEGER check(started_ts > 1443968369) PRIMARY KEY,\n" +
                        " name TEXT,\n" +
                        " status TEXT check(status in (\"local\", \"pending\", \"commit\")) NOT NULL DEFAULT \"local\",\n" +
                        " progress INTEGER check(progress >= 0 and (progress == 0 or status != \"local\")) NOT NULL DEFAULT 0,\n" +
                        " committed_ts INTEGER check(committed_ts > started_ts or status != \"committed\") NOT NULL DEFAULT 0\n" +
                        ");";
        final static String t = "insert into track (started_ts, name) values(?, ?)";
        final static String s = "insert into split (first_ts, track_id, data) values(?, ?, ?)";
    }

    private final SplitterParams mSplitter;
    protected final File mMainFolder;
    protected SQLiteDatabase buffer;
    protected List<SensorInfo> mSensorInfo = new ArrayList<>();
    protected List<ISensor> mSensors = new ArrayList<>();
    protected List<Litix.SensorData> mSensorData = new ArrayList<>();
    protected List<Litix.SensorEvent> mSensorEvent = new ArrayList<>();
    protected List<Litix.SessionMeta> mSessionMeta = new ArrayList<>();
    protected Object mSessionTag = "undefined";
    protected long mTrackID = 0;
    protected int splits = 0;
    protected int sampleSize = (Long.SIZE + 2 * Double.SIZE) / 8;

    public static class SplitterParams {
        private final float targetCompressedSize;
        private final float maxSplitTime;
        private final float ratioBalance = .35f;
        private final float adjustBalance = .4f;
        private float compressionRatio;
        private float adjust = 1;

        public SplitterParams(float targetCompressedSize, float maxSplitTime, float initialCompressionRatio) {
            this.targetCompressedSize = targetCompressedSize;
            this.maxSplitTime = maxSplitTime;
            compressionRatio = initialCompressionRatio;
        }

        public void updateCompressionRatio(float ratio) {
            Log.d("ProtoOut", "Updating CR: " + ratio);
            compressionRatio = compressionRatio * (1 - ratioBalance) + ratio * ratioBalance;
        }

        public void updateCompressedSize(float bytes) {
            Log.d("ProtoOut", "Updating CS: " + bytes);
            adjust *= (float)Math.pow(targetCompressedSize / bytes, adjustBalance);
        }

        public float getFlushSize() {
            return targetCompressedSize * adjust / compressionRatio;
        }

        @Override
        public String toString() {
            return "maxSplitTime:"+maxSplitTime+"\ttargetCompressedSize:"+targetCompressedSize+"\t\n" +
                    "ratio="+compressionRatio+"\tflushSize="+getFlushSize() + "\tadjust="+adjust;
        }
    }

    private String mName;
    private int mReceived = 0;
    private int mForwarded = 0;

    public ProtobufferOutput(String name, File dir, SplitterParams params) {
        mName = name;
        mMainFolder = new File(dir, getName());
        mSplitter = params;

        //noinspection ResultOfMethodCallIgnored
        mMainFolder.mkdirs();
        buffer = SQLiteDatabase.openOrCreateDatabase(new File(mMainFolder, "__buffers.db"), null);
        buffer.execSQL(Queries.i1);
        buffer.execSQL(Queries.i2);
    }

    public long currentBacklogSize() {
        return mSensorData.size() + mSensorEvent.size() + mSessionMeta.size();
    }

    public void flushTrackSplit() {
        Log.d("ProtoOut", "Flushing " + currentBacklogSize() + " SensorData/Event/Meta");
        final Long split_id = getMonoTimeMillis();

        Litix.TrackSplit.Builder sb = Litix.TrackSplit.newBuilder();
        sb.setTrackName(mSessionTag.toString());
        sb.addAllData(mSensorData);
        sb.addAllEvents(mSensorEvent);
        sb.addAllMeta(mSessionMeta);
        if (splits == 0)
            sb.addAllSensors(mSensorInfo);

        final Litix.TrackSplit ts = sb.build();

        // TODO test
        mSensorData.clear();
        mSensorEvent.clear();
        mSessionMeta.clear();

        new Thread(new Runnable() {
            @Override
            public void run() {
                long debugPreSize, debugTime = -System.nanoTime();
                debugPreSize = ts.getSerializedSize();
                debugTime += System.nanoTime();
                Log.d("ProtoOut", "Async compressing " + debugPreSize + " (computed in " + debugTime/1000_000.0 + "ms)");

                ByteArrayOutputStream b = new ByteArrayOutputStream((int)(mSplitter.getFlushSize() / sampleSize));
                try {
                    debugTime = -System.nanoTime();
                    GZIPOutputStream zos = new GZIPOutputStream(b);
                    ts.writeTo(zos);
                    zos.close();
                    byte[] ba = b.toByteArray();
                    debugTime += System.nanoTime();

                    Log.d("ProtoOut", "Async compressed " + ba.length + " (ratio " + (100.0 * ba.length / debugPreSize) + "%) in " + debugTime/1000_000.0 + "ms");

                    Log.v("ProtoOut", mSplitter.toString());

                    mSplitter.updateCompressedSize(ba.length);
                    mSplitter.updateCompressionRatio((float)ba.length / debugPreSize);

                    SQLiteStatement s = buffer.compileStatement(Queries.s);
                    s.clearBindings();
                    s.bindLong(1, split_id);
                    s.bindLong(2, mTrackID);
                    s.bindBlob(3, ba);
                    s.executeInsert();

                    b.close();

                    splits++;
                    ProtobufferOutput.this.mForwarded += currentBacklogSize();
                } catch (Exception e) {
                    Log.e("ProtoOut", "Flush error");
                    e.printStackTrace();
                }
            }
        }, "Flush").start();
    }

    private long bootUTCNanos = System.currentTimeMillis() * 1_000_000L + System.nanoTime();

    private long getMonoTimeMillis() {
        return (System.nanoTime() + bootUTCNanos) / 1_000_000L;
    }

    // OutputPlugIn implementation

    @Override
    public void outputPluginInitialize(Object sessionTag, List<ISensor> streamingSensors) {
        finalized = false;
        mSensors = streamingSensors;
        mSessionTag = sessionTag;
        mTrackID = getMonoTimeMillis();

        for (int s = 0; s < mSensors.size(); s++) {
            SensorInfo.Builder db = SensorInfo.newBuilder()
                    .setSensorId(s)
                    .setDevice(mSensors.get(s).getParentDevicePlugin().getClass().getName())
                    .setType(getClass().getName())
                    .setName(mSensors.get(s).getName());
            for (Object x : mSensors.get(s).getValueDescriptor())
                db.addChannels(x.toString());
            mSensorInfo.add(db.build());
        }

        SQLiteStatement stmt = buffer.compileStatement(Queries.t);
        stmt.bindLong(1, mTrackID);
        stmt.bindString(2, mSessionTag.toString());
        stmt.executeInsert();
    }

    private boolean finalized = true;

    @Override
    public void outputPluginFinalize() {
        flushTrackSplit();
        finalized = true;
    }

    @Override
    public void newSensorEvent(SensorEventEntry<Long> event) {
        mReceived++;
        mSensorEvent.add(Litix.SensorEvent.newBuilder()
                        .setTimestamp(event.timestamp)
                        .setCode(event.code)
                        .setMessage(event.message)
                        .setSensorId(mSensors.indexOf(event.sensor)) // FIXME inefficient
                        .build()
        );
        if (currentBacklogSize() >= mSplitter.getFlushSize() / sampleSize)
            flushTrackSplit();
    }

    // TODO Remove timestamp freedom degrees
    @Override
    public void newSensorData(SensorDataEntry<Long, double[]> data) {
        mReceived++;
        Double[] boxed = new Double[data.value.length];
        for (int i = 0; i < data.value.length; i++)
            boxed[i] = data.value[i];
        mSensorData.add(Litix.SensorData.newBuilder()
                .setTimestamp(data.timestamp)
                .addAllValue(Arrays.asList(boxed))
                .setSensorId(mSensors.indexOf(data.sensor)) // FIXME inefficient
                .build()
        );
        if (currentBacklogSize() >= mSplitter.getFlushSize() / sampleSize)
            flushTrackSplit();
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public void close() {
        if (!finalized)
            outputPluginFinalize();
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    @Override
    public int getReceivedMessagesCount() {
        return mReceived;
    }

    @Override
    public int getForwardedMessagesCount() {
        return mForwarded;
    }
}
