package com.cps.ocpp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Device {
    private String id;
    private String password;
}
