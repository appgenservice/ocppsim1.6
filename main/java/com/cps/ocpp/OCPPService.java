package com.cps.ocpp;

import eu.chargetime.ocpp.model.core.ChargePointStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Service
public class OCPPService {


    @PostConstruct
    public void init() {
        OCPPClient.simulateDevice(new Device("suby0200000324", "twoU#ojsPgVM.ARcX"));
    }

    public void changeStatus(String socketId, ChargePointStatus status) {
        OCPPClient.changeStatue(socketId, status);
    }

    public void addSocket(Device device) {
        OCPPClient.simulateDevice(device);
    }

    public void deleteSocket(String socketId) {
        OCPPClient.deleteSocket(socketId);
    }

    public List<String> getAllSockets() {
        return OCPPClient.getAllSockets();
    }

    public void startRFIDCharging(String socketId, String idTag) {
        OCPPClient.startRFIDCharging(socketId, idTag);
    }
}

