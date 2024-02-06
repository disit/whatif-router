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
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.OSMReaderConfig;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.OSMParsers;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.PointList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DynamicOSMReader extends OSMReader {

        /* I could also save the mapping between the way and the edges that belong to it, but I don't need it at the moment.
        private final Map<Long, List<Integer>> wayToEdgesMap = new HashMap<>();
        private int nextEdgeId = 0;

        // Inside addEdge(...):
        nextEdgeId++;   // Update the next edge id
        if (!wayToEdgesMap.containsKey(way.getId())) wayToEdgesMap.put(way.getId(), new ArrayList<>()); // Init the list of edges that belong to this way
        wayToEdgesMap.get(way.getId()).add(nextEdgeId); */

    // Save mapping between edge id and its way id
    // NOTE: Edge ids are incremental, starting from 0. It means I can use a simple list in order to store the way ids.
    private final List<Long> edgeToWayMap = new ArrayList<>();

    public DynamicOSMReader(BaseGraph baseGraph, EncodingManager encodingManager, OSMParsers osmParsers, OSMReaderConfig config) {
        super(baseGraph, encodingManager, osmParsers, config);
    }

    /**
     * Override the method that adds an edge to the graph, so that I can save the mapping between the way and the edges that belong to it
     *
     * @param fromIndex a unique integer id for the first node of this segment
     * @param toIndex   a unique integer id for the last node of this segment
     * @param pointList coordinates of this segment
     * @param way       the OSM way this segment was taken from
     * @param nodeTags  node tags of this segment if it is an artificial edge, empty otherwise
     */
    @Override
    protected void addEdge(int fromIndex, int toIndex, PointList pointList, ReaderWay way, Map<String, Object> nodeTags) {
        // NOTE: This method might be called multiple times for each way.
        // Every time this method is called, it means that a new edge is being added to the graph, with an increasing edgeId.
        // This means that I can count the edges that have already been added to the graph, so that I know the next edge id.
        super.addEdge(fromIndex, toIndex, pointList, way, nodeTags);

        // Update the mapping between the edge and the ways that it belongs to
        edgeToWayMap.add(way.getId());
    }

    public List<Long> getEdgeToWayMap() {
        return edgeToWayMap;
    }
}
