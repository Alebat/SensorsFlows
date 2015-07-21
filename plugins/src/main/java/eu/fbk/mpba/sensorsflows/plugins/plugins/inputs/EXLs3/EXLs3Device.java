package eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.EXLs3;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

import eu.fbk.mpba.sensorsflows.DevicePlugin;
import eu.fbk.mpba.sensorsflows.SensorComponent;
import eu.fbk.mpba.sensorsflows.base.IMonotonicTimestampReference;
import eu.fbk.mpba.sensorsflows.util.ReadOnlyIterable;

public class EXLs3Device implements DevicePlugin<Long, double[]>, IMonotonicTimestampReference {

    private String name;
    private EXLSensor monoSensor;

    EXLAccelerometer er;
    EXLBattery ry;
    EXLGyroscope pe;
    EXLMagnetometer ma;
    EXLQuaternion on;

    protected final int qDS, bDS;

    public EXLs3Device(BluetoothDevice realDevice, BluetoothAdapter adapter, String name, int quaternionDecimation, int batteryDecimation) {
        this.name = name;
        setBootUTCNanos();
        er = new EXLAccelerometer(this);
        ry = new EXLBattery(this);
        pe = new EXLGyroscope(this);
        ma = new EXLMagnetometer(this);
        on = new EXLQuaternion(this);
        monoSensor = new EXLSensor(this, realDevice, adapter);
        qDS = quaternionDecimation;
        bDS = batteryDecimation;
    }

    @Override
    public Iterable<SensorComponent<Long, double[]>> getSensors() {
        return new ReadOnlyIterable<>(Arrays.asList((SensorComponent<Long, double[]>) er, pe, ma, on, ry).iterator());
    }

    @Override
    public void inputPluginInitialize() {
        monoSensor.connect();
        monoSensor.switchDevOnAsync();
    }

    @Override
    public void inputPluginFinalize() {
        monoSensor.disconnect();
    }

    private long bootUTCNanos;

    public void setBootUTCNanos() {
        bootUTCNanos = System.currentTimeMillis() * 1000000 - System.nanoTime();
    }

    public long getMonoUTCNanos(long realTimeNanos) {
        return realTimeNanos + bootUTCNanos;
    }

    @Override
    public String getName() {
        return name;
    }

    public static class EXLSensor extends SensorComponent<Long, double[]> {

        boolean streaming = true;
        int received = 0;

        EXLs3Manager manager;
        EXLs3Device parent;
        String name;
        String address;
        BluetoothDevice dev;
        
        protected EXLSensor(EXLs3Device parent, BluetoothDevice device, BluetoothAdapter adapter) {
            this(parent);
            dev = device;
            address = device.getAddress();
            manager = new EXLs3Manager(btsStatus, btsData, device, adapter);
        }

        protected EXLSensor(EXLs3Device parent) {
            super(parent);
            this.parent = parent;
            name = parent.name;
        }

        @Override
        public List<Object> getValuesDescriptors() {
            throw new UnsupportedOperationException("Do not use this sensor.");
        }

        public void connect() {
            manager.connect();
        }

        public void disconnect() {
            manager.stop();
        }

        public void switchDevOnAsync() {
            manager.startStream();
        }

        public void switchDevOffAsync() {
            manager.stopStream();
        }

        @Override
        public void switchOnAsync() {
            streaming = true;
        }

        @Override
        public void switchOffAsync() {
            streaming = false;
        }

        @Override
        public String getName() {
            return "";
        }

        private final EXLs3Manager.StatusDelegate btsStatus = new EXLs3Manager.StatusDelegate() {

            public void idle(EXLs3Receiver sender) {
                sensorEvent(parent.getMonoUTCNanos(System.nanoTime()),
                        READY, "idle");
            }

            public void connecting(EXLs3Receiver sender, BluetoothDevice device, boolean secureMode) {
                parent.er.sensorEvent(parent.getMonoUTCNanos(System.nanoTime()),
                        CONNECTING, "connecting to " + device.getName() + "@" + device.getAddress() + (secureMode ? " secure" : " insecure") + " mode");
            }

            public void connected(EXLs3Receiver sender, String deviceName) {
                parent.er.sensorEvent(parent.getMonoUTCNanos(System.nanoTime()),
                        CONNECTED, "connected to " + deviceName);
            }

            public void disconnected(EXLs3Receiver sender, DisconnectionCause cause) {
                parent.er.sensorEvent(parent.getMonoUTCNanos(System.nanoTime()),
                        DISCONNECTED | cause.flag, "disconnected:" + cause.toString());
            }
        };

        private final long freq = 100;
        private final long max = 10000;
        private final long cycle = max * 1_000000L / freq;
        
        private final EXLs3Manager.DataDelegate btsData = new EXLs3Manager.DataDelegate() {
            long ref = -1;
            long now = -1;
            long pre = 0;
            int last = -1; // val ok
            int qd = 0, bd = 0;

            @Override
            public void received(EXLs3Manager sender, EXLs3Manager.Packet p) {
                // TODO! check timestamp calc
                now = parent.getMonoUTCNanos(p.receptionTime);
                if (ref < 0) {
                    pre = ref = now - p.counter * 1000_000000L / freq; // pk0 cTime = now - time from pk0 to pkThis
                }

                long calc = pre += (p.lostFromPreviousCounter(last) + 1) * 1000_000000L / freq;

                received++;

                if (parent.er.streaming)
                    parent.er.sensorValue(now, new double[]{p.ax, p.ay, p.az});
                if (parent.pe.streaming)
                    parent.pe.sensorValue(now, new double[]{p.gx, p.gy, p.gz});
                if (parent.ma.streaming)
                    parent.ma.sensorValue(now, new double[]{p.mx, p.my, p.mz});
                if (parent.on.streaming && (qd++ % parent.qDS) == 0)
                    parent.on.sensorValue(now, new double[]{p.q1, p.q2, p.q3, p.q4});
                if (parent.ry.streaming && (bd++ % parent.bDS) == 0)
                    parent.ry.sensorValue(now, new double[]{p.vbatt});

                last = p.counter;
            }

            @Override
            public void lost(EXLs3Manager sender, int from, int to, int howMany) {
                Log.v(this.getClass().getSimpleName(), "lost:" + howMany + " fr:" + from + " to:" + to);
            }
        };

        @Override
        public int getReceivedMessagesCount() {
            return received;
        }
    }

    public static class EXLAccelerometer extends EXLs3Device.EXLSensor {

        protected EXLAccelerometer(EXLs3Device parent) {
            super(parent);
        }

        public List<Object> getValuesDescriptors() {
            return Arrays.asList((Object) "ax", "ay", "az");
        }

    }

    public static class EXLGyroscope extends EXLs3Device.EXLSensor {

        protected EXLGyroscope(EXLs3Device parent) {
            super(parent);
        }

        public List<Object> getValuesDescriptors() {
            return Arrays.asList((Object) "gx", "gy", "gz");
        }

    }

    public static class EXLMagnetometer extends EXLs3Device.EXLSensor {

        protected EXLMagnetometer(EXLs3Device parent) {
            super(parent);
        }

        public List<Object> getValuesDescriptors() {
            return Arrays.asList((Object) "mx", "my", "mz");
        }

    }

    public static class EXLQuaternion extends EXLs3Device.EXLSensor {

        protected EXLQuaternion(EXLs3Device parent) {
            super(parent);
        }

        public List<Object> getValuesDescriptors() {
            return Arrays.asList((Object) "q1", "q2", "q3", "q4");
        }

    }

    public static class EXLBattery extends EXLs3Device.EXLSensor {

        protected EXLBattery(EXLs3Device parent) {
            super(parent);
        }

        public List<Object> getValuesDescriptors() {
            return Arrays.asList((Object) "vbatt");
        }

    }
}
