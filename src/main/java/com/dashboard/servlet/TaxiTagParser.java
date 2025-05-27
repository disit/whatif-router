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

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.Roundabout;
import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.CarTagParser;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.WayAccess;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.PMap;

public class TaxiTagParser extends CarTagParser{
    private static final String PREFIX = "taxi";
    private static final int MAX_SPEED = 100;
    private static final int PEDESTRIAN_SPEED = 30;

    public TaxiTagParser(EncodedValueLookup lookup, PMap properties) {
        this(
            lookup.getBooleanEncodedValue(VehicleAccess.key(PREFIX)),
            lookup.getDecimalEncodedValue(VehicleSpeed.key(PREFIX)),
            lookup.hasEncodedValue(TurnCost.key(PREFIX)) ? lookup.getDecimalEncodedValue(TurnCost.key(PREFIX)) : null,
            lookup.getBooleanEncodedValue(Roundabout.KEY),
            new PMap(properties).putObject("name", PREFIX),
            TransportationMode.PSV
        );
    }

    public TaxiTagParser(BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc, DecimalEncodedValue turnCostEnc,
                        BooleanEncodedValue roundaboutEnc, PMap properties, TransportationMode transportationMode) {
        super(accessEnc, speedEnc, turnCostEnc, roundaboutEnc, 
        new PMap(properties).putObject("name", PREFIX), transportationMode, speedEnc.getNextStorableValue(MAX_SPEED));

        restrictions.remove("motorcar");
        restrictions.add("psv");
        restrictions.add("bus");
        restrictions.add("taxi");
        restrictions.add("emergency");
        restrictions.add("motor_vehicle");

        restrictedValues.remove("no");
        restrictedValues.remove("private");
        restrictedValues.remove("restricted");
        restrictedValues.remove("emergency");

        barriers.remove("bus_trap");
        barriers.remove("sump_buster");
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way) {
        try{
            WayAccess access = getAccess(way);
            if (access == WayAccess.CAN_SKIP) 
                return edgeFlags;

            //pedestrian emergency & psv has to set allow directions and speed
            String highway = way.getTag("highway");
            boolean existence = vehicleTagExistence(way);
            if(highway != null){
                if("pedestrian".equals(highway) && existence){
                    //direct
                    accessEnc.setBool(false, edgeFlags, true);
                    avgSpeedEnc.setDecimal(false, edgeFlags, PEDESTRIAN_SPEED);
                    //reverse
                    accessEnc.setBool(true, edgeFlags, true);
                    avgSpeedEnc.setDecimal(true, edgeFlags, PEDESTRIAN_SPEED);
                    return edgeFlags;
                }
            }

            //go to super class
            edgeFlags = super.handleWayTags(edgeFlags, way);

            //in osm the road data can be monodirectional car lane marked with lanes:psv:backward so i need to allow go backwards
            String psvBackward = way.getTag("lanes:psv:backward");
            if(psvBackward != null)
                accessEnc.setBool(true, edgeFlags, true);            
            return edgeFlags;
        }catch(Exception e){
            System.err.println("Catching super handleWayTags");
            System.err.println(e.getMessage());
            return edgeFlags;
        }
    }

    @Override
    public WayAccess getAccess(ReaderWay way) {
        WayAccess access = super.getAccess(way);
        //emergency basic
        String emergency = way.getTag("emergency");
        String service = way.getTag("service");
        if ("yes".equals(emergency) && "emergency_access".equals(service)) {
            return WayAccess.WAY;
        }
        //pedestrian emergency
        String highway = way.getTag("highway");
        boolean existence = vehicleTagExistence(way);
        if ("pedestrian".equals(highway) && existence)
            return WayAccess.WAY;
        return access;
    }

    //check the existence of vehicle tag psv, emergency, taxi
    private boolean vehicleTagExistence(ReaderWay way){
        String psv = way.getTag("psv");
        String emergency = way.getTag("emergency");
        String taxi = way.getTag("taxi");

        boolean result = false;

        if(psv != null)
            result |= "yes".equals(psv);
        if(emergency != null)
            result |= "yes".equals(emergency);
        if(taxi != null)
            result |= "yes".equals(taxi);
        
        return result;
    }
}
