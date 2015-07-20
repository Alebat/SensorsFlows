package eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.CSVLoader;

import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

import eu.fbk.mpba.sensorsflows.DevicePlugin;
import eu.fbk.mpba.sensorsflows.SensorComponent;
import eu.fbk.mpba.sensorsflows.base.IMonotonicTimestampReference;


/**
 * [IMPORTANTE] Vai a vedere il javadoc di CSVLoaderSensor,
 * non lo riporto qui per non creare ridondanza ovviamente.
*/
public class CSVLoaderDevice implements DevicePlugin<Long, double[]>, IMonotonicTimestampReference {
    protected List<SensorComponent<Long, double[]>> _sensors;
    protected String _name;
    protected Runnable onfinish = null;

    /**
     * @param name nome del Device
     *             I vari file vanno aggiunti con addFile.
     */
    public CSVLoaderDevice(String name) {
        _name = name;
        _sensors = new LinkedList<>();
    }

    /**
     * Thread con cui i sensori a turno caricano i loro dati.
     */
    private Thread thr = new Thread(new Runnable() {
        @Override
        public void run() {
            for (SensorComponent s : _sensors)
                s.switchOnAsync();

            boolean ceAncoraSperanza;
            do {
                ceAncoraSperanza = false;
                for (SensorComponent s : _sensors) {
                    if (((CSVLoaderSensor) s).sendRow())
                        ceAncoraSperanza = true;
                }
            } while (ceAncoraSperanza);


            if (onfinish != null)
                onfinish.run();
        }
    });

    /**
     * @param is             stream di input
     * @param fieldSeparator separatore dei vari campi
     * @param rowSeparator   separatore delle varie righe di campi.
     *                       
     *                       AVVERTENZA: ricordati che "\n" e' diverso da "\r\n" ovviamente.
     */
    public void addFile(InputStreamReader is, String fieldSeparator, String rowSeparator, long tsScale, String name) throws Exception {
        _sensors.add(new CSVLoaderSensor(is, fieldSeparator, rowSeparator, tsScale, name, this));
    }

    public void addFile(InputStreamReader is, String fieldSeparator, String rowSeparator, long tsScale) throws Exception {
        addFile(is, fieldSeparator, rowSeparator, tsScale, "");
    }

    public void addFile(InputStreamReader is, String fieldSeparator, String rowSeparator, String name) throws Exception {
        addFile(is, fieldSeparator, rowSeparator, 1, name);
    }

    public void addFile(InputStreamReader is, String fieldSeparator, String rowSeparator) throws Exception {
        addFile(is, fieldSeparator, rowSeparator, 1, "");
    }


    public void setAsyncActionOnFinish(Runnable action) {
        onfinish = action;
    }

    /**
     * Faccio partire i sensori
     * Chiamato almeno una volta
     */
    @Override public void inputPluginInitialize() {
        //Thread per un'esecuzione non bloccante.
        thr.start();
    }

    @Override public void inputPluginFinalize(){}

    @Override
    public Iterable<SensorComponent<Long, double[]>> getSensors() {
        return _sensors;
    }

    @Override
    public String getName() {
        return _name;
    }


    //Questi metodi sono per il timestamp, non sono metodi miei quindi non saprei documentarli
    private long refUTCNanos;
    public void setBootUTCNanos() {
        refUTCNanos = System.currentTimeMillis() * 1000000 - System.nanoTime();
    }
    public long getMonoUTCNanos(long realTimeNanos) {
        return realTimeNanos + refUTCNanos;
    }
}
