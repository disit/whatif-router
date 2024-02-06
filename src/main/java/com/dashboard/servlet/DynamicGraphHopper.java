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

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.osm.Pair;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.BlockAreaWeighting;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphEdgeIdFinder;
import com.graphhopper.util.*;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;


public class DynamicGraphHopper extends GraphHopper {
    private GraphEdgeIdFinder.BlockArea blockArea;  // Area to avoid during routing

    // Save mapping between edge id and its way id
    // NOTE: Edge ids are incremental, starting from 0. It means I can use a simple list in order to store the mapping between edge (whose is is the position) way id (the value, representing the way id)
    private List<Long> edgeToWayMap = new ArrayList<>();

    // Traffic data of the day and hour of the start of the routing.
    // RoadElement id => Pair of average traffic density and maximum traffic density for that road element
    private final Map<String, Pair<Float, Float>> trafficData = new HashMap<>();


    public DynamicGraphHopper(LocalDateTime startTimestamp) {
        super();
        loadTrafficData(startTimestamp);
        if (edgeToWayMap.isEmpty()) {
            // Check if the file containing the mappings between the edges and the ways exist, and if so, deserialize it
            if (new File(getGraphHopperLocation() + "/edgeToWayMap.json").exists())
                deserializeMapping();
        }
    }

    // Override the createWeighting method of the GraphHopper class to enable BlockAreaWeighting
    @Override
    protected WeightingFactory createWeightingFactory() {
        // Get encoded values for the vehicle
        EncodingManager em = this.getEncodingManager();
        BooleanEncodedValue accessEnc = em.getBooleanEncodedValue(VehicleAccess.key(this.getProfiles().get(0).getVehicle()));
        DecimalEncodedValue speedEnc = em.getDecimalEncodedValue(VehicleSpeed.key(this.getProfiles().get(0).getVehicle()));

        // Get the weighting in use
        String weighting = this.getProfiles().get(0).getWeighting();
        WeightingFactory result;

        if (weighting.equals("fastest_with_traffic")) {
            if (edgeToWayMap.isEmpty()) {
                result = (Profile profile, PMap hints, boolean disableTurnCosts) -> new FastestWeighting(accessEnc, speedEnc);
            }
            else {
                result = (Profile profile, PMap hints, boolean disableTurnCosts) -> new FastestWeightingWithTraffic(trafficData, accessEnc, speedEnc, edgeToWayMap);
            }
        }
        // Other default weightings, like "shortest", "short_fastest", etc. See https://github.com/graphhopper/graphhopper/blob/master/docs/core/profiles.md
        else result = super.createWeightingFactory();

        // Add the blockArea to the weighting
        if (blockArea != null) {
            // Create a new WeightingFactory, with the createWeighting method that returns a BlockAreaWeighting if a BlockArea is set and uses the "result" weighting otherwise
            return (Profile profile, PMap hints, boolean disableTurnCosts) -> {
                Weighting w = result.createWeighting(profile, hints, disableTurnCosts);
                return new BlockAreaWeighting(w, blockArea);
            };
        }
        else return result;
    }

    public void setBlockArea(GraphEdgeIdFinder.BlockArea ba) {
        blockArea = ba;
    }

    /**
     * Imports provided data from disc and creates graph.
     * Depending on the settings the resulting graph will be stored to disc so on a second call this method will only load the graph from disc which is usually a lot faster.
     * This will also create and save the mappings between the way and the edges that belong to it (or load them if it isn't the first execution).
     */
    @Override
    public DynamicGraphHopper importOrLoad() {
        if (!load()) {
            // If the graph cannot be loaded, then create it
            process(false);

            // Generate the mappings between the way and the edges that belong to it
            DynamicOSMReader reader = new DynamicOSMReader(GHUtility.newGraph(getBaseGraph()), getEncodingManager(), getOSMParsers(), getReaderConfig());
            reader.setFile(new File(getOSMFile()));
            try {
                reader.readGraph();
                edgeToWayMap = reader.getEdgeToWayMap();
                serializeMapping();    // Save the mappings between the way and the edges
            } catch (IOException e) {
                System.out.println("Error while reading the graph");
            }
        }
        else deserializeMapping();  // Load the mappings between the way and the edges
        return this;
    }

    /**
     * Serialize the mappings between the edge and the way in which it belongs, saving them in a file
     */
    private void serializeMapping() {
        try {
            // Serialize the edgeToWayMap
            FileUtils.writeLines(new File(getGraphHopperLocation() + "/edgeToWayMap.json"), Collections.singleton(new JSONArray(edgeToWayMap)));
        } catch (IOException e) {
            System.out.println("Error while serializing the mappings");
        }
    }

    /**
     * Read the mappings between the edge and the way in which it belongs, saved in a file and deserialize them (recreate the edgeToWayMap)
     */
    private void deserializeMapping() {
        try {
            // Deserialize the edgeToWayMap
            String json = FileUtils.readFileToString(new File(getGraphHopperLocation() + "/edgeToWayMap.json"));
            JSONArray jsonArray = new JSONArray(json);
            // For each edge, get the way it belongs to
            for (int i = 0; i < jsonArray.length(); i++) {
                edgeToWayMap.add(jsonArray.getLong(i));
            }
        } catch (IOException e) {
            System.out.println("Error while deserializing the edgeToWayMap");
        }
    }

    /**
     * Initialize the traffic data map of the day and hour of the start of the routing.
     * <p>
     * This method reads a JSON file from the "typical_ttt" folder, named D_HH.json, where D is the day of the week (0 = Monday, 6 = Sunday) and HH is the hour of the day (00, 01, ..., 23).
     * The JSON file contains a map of the road elements and their average and maximum traffic density.
     * Format: { "roadElementId": { "ttt": float, "max": float }, ... }
     *
     * @param startTimestamp the timestamp of the start of the routing, which is used to get the day and hour of the routing
     */
    private void loadTrafficData(LocalDateTime startTimestamp) {
        String day = String.valueOf(startTimestamp.getDayOfWeek().getValue() - 1); // Day of the week (0 = Monday, 6 = Sunday)
        String hour = String.format("%02d", startTimestamp.getHour());  // Hour of the day (00, 01, ..., 23)

        // Read the JSON file
        try {
            String typicalTttPath = System.getenv("GH_TYPICAL_TTT_PATH");
            if(typicalTttPath == null)
                typicalTttPath = "typical_ttt";
            String json = FileUtils.readFileToString(new File(typicalTttPath + "/" + day + "_" + hour + ".json"));
            JSONObject jsonObject = new JSONObject(json);
            jsonObject.keys().forEachRemaining(keyStr -> {
                // For each road element, get the average and maximum traffic density
                JSONObject roadElement = jsonObject.getJSONObject(keyStr);
                float ttt = roadElement.getFloat("ttt");
                float max = roadElement.getFloat("max");

                // Add the road element and its traffic data to the traffic data map
                trafficData.put(keyStr, new Pair<>(ttt, max));
            });
        } catch (IOException e) {
            System.out.println("Error while reading the traffic data: " + e.getMessage());
        }
    }
}

