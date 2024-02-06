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

import com.graphhopper.reader.osm.Pair;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.weighting.*;
import com.graphhopper.util.EdgeIteratorState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension of FastestWeighting that considers the traffic data to update the weight of the edges.
 *
 * @see com.graphhopper.routing.weighting.FastestWeighting
 */
public class FastestWeightingWithTraffic extends FastestWeighting {

    // Save mapping between edge id and its way id
    // NOTE: Edge ids are incremental, starting from 0.
    // It means I can use a simple list in order to store the mapping between edge (whose is is the position) way id (the value, representing the way id)
    private final List<Long> edgeToWayMap;

    // Traffic data of the day and hour of the start of the routing.
    // RoadElement id => Pair of average traffic density and maximum traffic density for that road element
    private final Map<String, Pair<Float, Float>> trafficData = new HashMap<>();

    private final double maxSpeed;  // Maximum speed of the considered road

    public FastestWeightingWithTraffic(Map<String, Pair<Float, Float>> trafficData, BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc, List<Long> edgeToWayMap) {
        this(trafficData, accessEnc, speedEnc, TurnCostProvider.NO_TURN_COST_PROVIDER, edgeToWayMap);
    }

    public FastestWeightingWithTraffic(Map<String, Pair<Float, Float>> trafficData, BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc, TurnCostProvider tcProvider, List<Long> edgeToWayMap) {
        super(accessEnc, speedEnc, tcProvider);
        this.edgeToWayMap = edgeToWayMap;
        this.trafficData.putAll(trafficData);
        maxSpeed = speedEnc.getMaxOrMaxStorableDecimal() / SPEED_CONV;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        try {
            return getEdgeTravelTime(edgeState);
        } catch (IndexOutOfBoundsException e) { // If the edge is not in the map, it means that I can't get its traffic data
            return super.calcEdgeWeight(edgeState, reverse);
        }
    }

    /**
     * Use the traffic data to calculate the travel time of the edge.
     * If the edge is not in the map, it means that I can't get its traffic data, so I calculate the travel time at max speed.
     *
     * @param edgeState edge to calculate the travel time of
     * @return the travel time of the edge
     */
    private double getEdgeTravelTime(EdgeIteratorState edgeState) {

        // Get the traffic data for each road element that belongs to the way (the RoadElement id contains the Way id)
        long wayId = edgeToWayMap.get(edgeState.getEdge());
        List<Float> averageDensityList = new ArrayList<>();
        float maxDensity = 0;

        List<String> roadElementsOfWay = new ArrayList<>();
        trafficData.keySet().forEach(roadElementId -> {
            if (roadElementId.contains(Long.toString(wayId))) roadElementsOfWay.add(roadElementId);
        });

        for (String roadElementId : roadElementsOfWay) {
            Pair<Float, Float> trafficData = this.trafficData.get(roadElementId);
            averageDensityList.add(trafficData.first);
            maxDensity = trafficData.second;
        }

        // Calculate the average traffic density
        float averageDensity = averageDensityList.stream().reduce(0f, Float::sum) / averageDensityList.size();

        // If the way has no traffic data, return the travel time of the edge without considering the traffic
        if (roadElementsOfWay.isEmpty()) return super.calcEdgeWeight(edgeState, false);
        // If the average traffic density is greater than the maximum traffic density, return infinity (critical condition, the road is blocked from the traffic)
        if (averageDensity > maxDensity) return Double.POSITIVE_INFINITY;

        // Calculate the speed
        double speed = maxSpeed * (1 - averageDensity / maxDensity);    // As the density increases, the speed decreases (Greenshield's model)
        return edgeState.getDistance() / speed * SPEED_CONV;
    }

    @Override
    public String getName() {
        return "fastest_with_traffic";
    }
}