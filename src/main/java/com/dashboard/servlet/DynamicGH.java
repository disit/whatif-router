/* WhatIfRouter
 Copyright (C) 2019 DISIT Lab http://www.disit.org - University of Florence
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

import com.graphhopper.GraphHopper;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.routing.weighting.BlockAreaWeighting;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphEdgeIdFinder;

public class DynamicGH extends GraphHopperOSM {
    private GraphEdgeIdFinder.BlockArea blockArea;
    public DynamicGH() {
        super();
    }
    // Override the createWeighting method of the GraphHopper class to enable BlockAreaWeighting
    @Override
    public Weighting createWeighting(HintsMap hintsMap, FlagEncoder encoder, Graph graph) {
        System.out.println("create weighting: "+hintsMap.getWeighting() );
        String weighting = hintsMap.getWeighting();
        if ("block_area".equalsIgnoreCase(weighting)) {
            return new BlockAreaWeighting(new FastestWeighting(encoder), blockArea);
        } else {
            return super.createWeighting(hintsMap, encoder, graph);
        }
    }

    public void setBlockArea(GraphEdgeIdFinder.BlockArea ba) {
        blockArea = new GraphEdgeIdFinder.BlockArea(this.getGraphHopperStorage());
        blockArea = ba;
    }
}