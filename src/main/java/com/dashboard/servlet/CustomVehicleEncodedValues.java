/* WhatIfRouter
 Copyright (C) 2023 DISIT Lab http://www.disit.org - University of Florence
 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.
 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.
 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package com.dashboard.servlet;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

public class CustomVehicleEncodedValues {
    //create vehicle encoded values
    public static VehicleEncodedValues taxi(PMap properties) {
        String name = properties.getString("name", "taxi");
        int speedBits = properties.getInt("speed_bits", 5);
        double speedFactor = properties.getDouble("speed_factor", 5);
        boolean speedTwoDirections = properties.getBool("speed_two_directions", true); //default=true because pedestrian ways can be used in both directions
        int maxTurnCosts = properties.getInt("max_turn_costs", properties.getBool("turn_costs", false) ? 1 : 0);
        BooleanEncodedValue accessEnc = VehicleAccess.create(name);
        DecimalEncodedValue speedEnc = VehicleSpeed.create(name, speedBits, speedFactor, speedTwoDirections);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(name, maxTurnCosts) : null;
        return new VehicleEncodedValues(name, accessEnc, speedEnc, null,  turnCostEnc);
    }
}
