package eu.fbk.mpba.sensorsflowsa;

import eu.fbk.mpba.sensorsflowsa.base.DeviceStatus;
import eu.fbk.mpba.sensorsflowsa.base.IDevice;

/**
 * This class adds internal support for the library data-paths.
 */
public abstract class DevicePlugIn<TimeT, ValueT> implements IDevice<SensorComponent<TimeT, ValueT>> {
    FlowsMan<TimeT, ValueT> _manager = null;
    private DeviceStatus _status = DeviceStatus.NOT_INITIALIZED;

    void setOutputCallbackManager(FlowsMan<TimeT, ValueT> manager) {
        _manager = manager;
    }

    protected void changeState(DeviceStatus s) {
        if (_manager != null)
            _manager.deviceStateChanged(this, _status = s);
    }

    @Override
    public void initialize() {
        changeState(DeviceStatus.INITIALIZING);
        pluginInitialize();
        changeState(DeviceStatus.INITIALIZED);
    }

    @Override
    public void finalizeDevice() {
        changeState(DeviceStatus.FINALIZING);
        pluginFinalize();
        changeState(DeviceStatus.FINALIZED);
    }

    // Getters

    @Override
    public DeviceStatus getState() {
        return _status;
    }

    // Abstracts to be implemented by the plug-in

    protected abstract void pluginInitialize();

    protected abstract void pluginFinalize();

}
