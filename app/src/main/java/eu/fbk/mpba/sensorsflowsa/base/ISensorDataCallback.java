package eu.fbk.mpba.sensorsflowsa.base;

/**
 * Main interface for a sensor's data and state callback.
 * Multiple sensors call this so the sender parameter is the sender sensor.
 * The receiver should implement this.
 */
public interface ISensorDataCallback<SensorT extends ISensor, TimeT, ValueT> {

    void sensorStateChanged(SensorT sensor, TimeT time, SensorStatus state);

    void sensorEvent(SensorT sensor, TimeT time, int type, String message);

    void sensorValue(SensorT sensor, TimeT time, ValueT value);
}
