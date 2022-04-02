package com.cps.ocpp;

import eu.chargetime.ocpp.IClientAPI;
import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.JSONConfiguration;
import eu.chargetime.ocpp.OccurenceConstraintException;
import eu.chargetime.ocpp.UnsupportedFeatureException;
import eu.chargetime.ocpp.feature.profile.ClientCoreEventHandler;
import eu.chargetime.ocpp.feature.profile.ClientCoreProfile;
import eu.chargetime.ocpp.model.Request;
import eu.chargetime.ocpp.model.core.AuthorizationStatus;
import eu.chargetime.ocpp.model.core.AuthorizeConfirmation;
import eu.chargetime.ocpp.model.core.AuthorizeRequest;
import eu.chargetime.ocpp.model.core.AvailabilityStatus;
import eu.chargetime.ocpp.model.core.BootNotificationRequest;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityConfirmation;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.model.core.ChangeConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.ChangeConfigurationRequest;
import eu.chargetime.ocpp.model.core.ChargePointErrorCode;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.ClearCacheConfirmation;
import eu.chargetime.ocpp.model.core.ClearCacheRequest;
import eu.chargetime.ocpp.model.core.DataTransferConfirmation;
import eu.chargetime.ocpp.model.core.DataTransferRequest;
import eu.chargetime.ocpp.model.core.GetConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.GetConfigurationRequest;
import eu.chargetime.ocpp.model.core.Location;
import eu.chargetime.ocpp.model.core.MeterValue;
import eu.chargetime.ocpp.model.core.MeterValuesRequest;
import eu.chargetime.ocpp.model.core.RemoteStartStopStatus;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionConfirmation;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionRequest;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionConfirmation;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionRequest;
import eu.chargetime.ocpp.model.core.ResetConfirmation;
import eu.chargetime.ocpp.model.core.ResetRequest;
import eu.chargetime.ocpp.model.core.SampledValue;
import eu.chargetime.ocpp.model.core.StartTransactionConfirmation;
import eu.chargetime.ocpp.model.core.StartTransactionRequest;
import eu.chargetime.ocpp.model.core.StopTransactionRequest;
import eu.chargetime.ocpp.model.core.UnlockConnectorConfirmation;
import eu.chargetime.ocpp.model.core.UnlockConnectorRequest;
import eu.chargetime.ocpp.model.core.ValueFormat;
import eu.chargetime.ocpp.wss.WssSocketBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


@Slf4j
public class OCPPClient {
    public static final String SAMPLE_PERIODIC = "Sample.Periodic";
    public static final String ENERGY_ACTIVE_IMPORT_REGISTER = "Energy.Active.Import.Register";
    public static final String L_1 = "L1";
    public static final String WH = "Wh";
    private static Map<String, OCPPClient> sockets = new HashMap<>();
    public static final String WSS_URL = "wss://ocpp-onedotsix-k8s-staging-01.ubitricity.com:9443";
    private IClientAPI client;
    private ClientCoreProfile core;
    private Device device;
    private ChargePointStatus status;
    private static final long STARTING_METER_VALUE = 1646383221897L;
    private int CONNECTOR_ID = 1;
    Timer timer = null;

    public OCPPClient(Device device) {
        this.device = device;
    }

    public static void changeStatue(String socketId, ChargePointStatus status) {
        OCPPClient client = sockets.get(socketId);
        client.sendStatusNotification(status);
    }

    public static void deleteSocket(String socketId) {
        OCPPClient ocppClient = sockets.get(socketId);
        if (ocppClient != null) {
            ocppClient.disconnect();
            sockets.remove(socketId);
        }
    }

    public static List<String> getAllSockets() {
        return new ArrayList(sockets.keySet());
    }

    public static void startRFIDCharging(String socketId, String idTag) {
        OCPPClient client = sockets.get(socketId);
        var request = client.core.createAuthorizeRequest(idTag);
        client.sendAuthenticationRequest(client.CONNECTOR_ID, idTag, true, true);
    }

    private void sendAuthenticationRequest(Integer connectorId, String idTag, boolean start, boolean ignoreAuthStatus) {
        AuthorizeRequest authorizeRequest = new AuthorizeRequest(idTag);

        try {
            client.send(authorizeRequest).whenComplete((confirmation, throwable) -> {
                AuthorizeConfirmation authorizeConfirmation = (AuthorizeConfirmation) confirmation;
                log.info("Socket: {}, AuthorizeConfirmation: {}", device.getId(), authorizeConfirmation);
                if (ignoreAuthStatus || authorizeConfirmation.getIdTagInfo().getStatus() == AuthorizationStatus.Accepted) {
                    if (start) {
                        sendStartTransactionRequest(connectorId, idTag);
                    } else {
                        //stop transaction
                    }
                } else {
                    log.error("Authorization request failed: socketId: {}, status: {}", device.getId(), authorizeConfirmation.getIdTagInfo().getStatus());
                }
            });
        } catch (OccurenceConstraintException e) {
            e.printStackTrace();
        } catch (UnsupportedFeatureException e) {
            e.printStackTrace();
        }
    }

    private void sendStartTransactionRequest(Integer connectorId, String idTag) {
        StartTransactionRequest startTransactionRequest = new StartTransactionRequest(connectorId, idTag, getCurrentMeterValue(), ZonedDateTime.now());
        try {
            client.send(startTransactionRequest).whenComplete((confirmation, throwable) -> {

                StartTransactionConfirmation startTransactionConfirmation = (StartTransactionConfirmation) confirmation;
                Integer currentTxnId = startTransactionConfirmation.getTransactionId();
                sendStatusNotification(ChargePointStatus.Charging);
                log.info("Socket: {} -  Successfully started new transaction: {}", device.getId(), currentTxnId);
                log.info("Starting Meter Value timer for {}", device.getId());
                timer = new Timer();
                timer.scheduleAtFixedRate(new MeterValueTask(currentTxnId), 60000L, 60000L);
            });
        } catch (OccurenceConstraintException e) {
            e.printStackTrace();
        } catch (UnsupportedFeatureException e) {
            e.printStackTrace();
        }
    }

    public void connect() {
        // The core profile is mandatory
        this.core = new ClientCoreProfile(new ClientCoreEventHandler() {
            int currentTxnId = 0;

            @Override
            public ChangeAvailabilityConfirmation handleChangeAvailabilityRequest(ChangeAvailabilityRequest request) {

                System.out.println(request);
                // ... handle event

                return new ChangeAvailabilityConfirmation(AvailabilityStatus.Accepted);
            }

            @Override
            public GetConfigurationConfirmation handleGetConfigurationRequest(GetConfigurationRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }

            @Override
            public ChangeConfigurationConfirmation handleChangeConfigurationRequest(ChangeConfigurationRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }

            @Override
            public ClearCacheConfirmation handleClearCacheRequest(ClearCacheRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }

            @Override
            public DataTransferConfirmation handleDataTransferRequest(DataTransferRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }

            @Override
            public RemoteStartTransactionConfirmation handleRemoteStartTransactionRequest(RemoteStartTransactionRequest request) {

                log.info("Rquest: {}", request);
                // ... handle event
                if (currentTxnId != 0) {
                    log.info("Socket: {} - Can't start new charging, there is already one on going transaction going on: " + currentTxnId);
                    return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Rejected);
                }
                if (request.getConnectorId() == null) {//Hubject eRoaming / connetorId is coming as null
                    log.info("Remote Start Req {} , connectorId = null, setting to {}", device.getId(), request);
                    request.setConnectorId(CONNECTOR_ID);
                }
                sendAuthenticationRequest(CONNECTOR_ID, request.getIdTag(), true, true);
                return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Accepted);
            }


            @Override
            public RemoteStopTransactionConfirmation handleRemoteStopTransactionRequest(RemoteStopTransactionRequest request) {
                if (timer != null) {
                    timer.cancel();
                }
                sendStatusNotification(ChargePointStatus.Finishing);
                if (request.getTransactionId() != currentTxnId) {
                    log.info("Socket: {} - Stop TxnId = {} is not matching with on going TxnId: {}", request.getTransactionId(), currentTxnId);
                    return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Rejected);
                }
                StopTransactionRequest stopTransactionRequest = new StopTransactionRequest(getCurrentMeterValue(), ZonedDateTime.now(), request.getTransactionId());
                try {
                    client.send(stopTransactionRequest).whenComplete((confirmation, throwable) -> {
                        log.info("Socket: {} - On going transaction stopped successfully. Txn Id: {}", device.getId(), request.getTransactionId());
                        currentTxnId = 0;
                        sendStatusNotification(ChargePointStatus.Preparing);
                    });
                } catch (OccurenceConstraintException e) {
                    e.printStackTrace();
                } catch (UnsupportedFeatureException e) {
                    e.printStackTrace();
                }

                // ... handle event

                return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Accepted);
            }

            @Override
            public ResetConfirmation handleResetRequest(ResetRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }

            @Override
            public UnlockConnectorConfirmation handleUnlockConnectorRequest(UnlockConnectorRequest request) {

                System.out.println(request);
                // ... handle event

                return null; // returning null means unsupported feature
            }
        });
        JSONConfiguration jsonConfiguration = JSONConfiguration.get();
        jsonConfiguration.setParameter(JSONConfiguration.USERNAME_PARAMETER, device.getId());
        jsonConfiguration.setParameter(JSONConfiguration.PASSWORD_PARAMETER, device.getPassword());
        WssSocketBuilder wssSocketBuilder = new WssSocketBuilder() {
            URI uri;

            @Override
            public WssSocketBuilder uri(URI uri) {
                this.uri = uri;
                return this;
            }

            @Override
            public Socket build() throws IOException {
                return null;
            }

            @Override
            public void verify() {

            }
        };
        client = new JSONClient(core, device.getId(), wssSocketBuilder, jsonConfiguration);
        client.connect(WSS_URL, null);

        try {
            sendBootNotification(device.getId());
            sendStatusNotification(ChargePointStatus.Available);//com.cps.ocpp.Device up, cable not plugged in
            sendStatusNotification(ChargePointStatus.Preparing);//Cable plugged in, ready for charging
        } catch (Exception e) {
            log.error("Error while bootstraping device sim: {}", device.getId(), e);
        }

    }


    public void sendStatusNotification(ChargePointStatus status) {
        this.status = status;
        // Use the feature profile to help create event
        Request request = core.createStatusNotificationRequest(CONNECTOR_ID, ChargePointErrorCode.NoError, status);

        // Client returns a promise which will be filled once it receives a confirmation.
        try {
            client.send(request).whenComplete((s, ex) -> System.out.println(s));
        } catch (OccurenceConstraintException e) {
            e.printStackTrace();
        } catch (UnsupportedFeatureException e) {
            e.printStackTrace();
        }
        log.info("Socket: {} status changed to {}", device.getId(), this.status);
    }

    public void sendBootNotification(String deviceId) throws Exception {

        BootNotificationRequest request = core.createBootNotificationRequest("ubitricity", "sso1008-hs-1.1.1");
        request.setChargePointSerialNumber(deviceId);
        request.setChargeBoxSerialNumber(deviceId);
        request.setFirmwareVersion("b1000c_mmsda-fs-2.23.0");
        request.setMeterType("Microchip ATM90E26");
        // Client returns a promise which will be filled once it receives a confirmation.
        client.send(request).whenComplete((s, ex) -> System.out.println(s));
    }


    public void disconnect() {
        client.disconnect();
    }

    //    public static void main(String[] args) {
//        simulateDevice("suby0200000318", "CL_TEST_AUTH_PW_UBI");
//        simulateDevice("suby010000001", "CL_TEST_AUTH_PW_UBI");
//        simulateDevice("suby010000002", "CL_TEST_AUTH_PW_UBI");
//        simulateDevice("suby010000003", "CL_TEST_AUTH_PW_UBI");
//    }
//
    public static void simulateDevice(Device device) {
        OCPPClient client = new OCPPClient(device);
        client.connect();
        sockets.put(device.getId(), client);
        log.info("Bootstrapped socket sim Id: {}", device.getId());

    }

    public int getCurrentMeterValue() {
        return (int) ((System.currentTimeMillis() - STARTING_METER_VALUE) / 1000 / 60);
    }

    class MeterValueTask extends TimerTask {
        int currentTxnId = 0;

        public MeterValueTask(int currentTxnId) {
            this.currentTxnId = currentTxnId;
        }

        @Override
        public void run() {
            log.info("About to send MeterValue for device: {}, txnId: {}", device.getId(), currentTxnId);
            sendMeterValue();
        }

        private void sendMeterValue() {
            MeterValuesRequest meterValuesRequest = new MeterValuesRequest(CONNECTOR_ID);
            meterValuesRequest.setTransactionId(currentTxnId);
            SampledValue sampledValue = new SampledValue(String.valueOf(getCurrentMeterValue()));
            sampledValue.setContext(SAMPLE_PERIODIC);
            sampledValue.setFormat(ValueFormat.Raw);
            sampledValue.setLocation(Location.Outlet);
            sampledValue.setMeasurand(ENERGY_ACTIVE_IMPORT_REGISTER);
            sampledValue.setPhase(L_1);
            sampledValue.setUnit(WH);
            meterValuesRequest.setMeterValue(new MeterValue[]{new MeterValue(ZonedDateTime.now(), new SampledValue[]{sampledValue})});
            try {
                client.send(meterValuesRequest).whenComplete((confirmation, throwable) -> {
                    log.info("Meter Value {} send for {}", sampledValue.getValue(), device);
                });
            } catch (OccurenceConstraintException e) {
                e.printStackTrace();
            } catch (UnsupportedFeatureException e) {
                e.printStackTrace();
            }
        }
    }
}
