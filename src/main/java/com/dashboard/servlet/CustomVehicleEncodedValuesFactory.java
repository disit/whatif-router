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

import com.graphhopper.routing.util.DefaultVehicleEncodedValuesFactory;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

public class CustomVehicleEncodedValuesFactory extends DefaultVehicleEncodedValuesFactory{
    String TAXI = "taxi";

    @Override
    public VehicleEncodedValues createVehicleEncodedValues(String name, PMap configuration){
        //create vehicle encoded values
        if(name.equals(TAXI)){
            return CustomVehicleEncodedValues.taxi(configuration);
        }
        //use predefined encoded values
        return super.createVehicleEncodedValues(name, configuration);
    }
}