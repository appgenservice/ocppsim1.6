package com.cps.controller;

import com.cps.ocpp.Device;
import com.cps.ocpp.OCPPService;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value="/simulator")
@AllArgsConstructor
public class SimulatorController {


    private final OCPPService ocppService;

    @PutMapping("/status/{socketId}/{status}")
    public void plugIn(@PathVariable("socketId") String socketId, @PathVariable("status") ChargePointStatus status)  {
        ocppService.changeStatus(socketId, status);
    }

    @PostMapping("/rfid/start/{socketId}/{idTag}")
    public void addSocket(@PathVariable("socketId") String socketId, @PathVariable("idTag") String idTag)  {
        ocppService.startRFIDCharging(socketId, idTag);
    }

    @PostMapping
    public void addSocket(@RequestBody Device device)  {
        ocppService.addSocket(device);
    }

    @DeleteMapping("/{socketId}")
    public void deleteSocket(@PathVariable("socketId") String socketId)  {
        ocppService.deleteSocket(socketId);
    }

    @GetMapping
    public List<String> getSockets()  {
        return ocppService.getAllSockets();
    }
}
