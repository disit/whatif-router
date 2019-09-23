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

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.gson.Gson;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.GraphEdgeIdFinder;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

@Path("/route")
public class Test {
    /**
     * API interface method called by Dashboard
     * @param avoidArea FeatureCollection object (in GeoJSON format) containing the areas to avoid in routing calculation
     * @param waypoints Routing lat/lng waypoints separed by ';'
     * @return the Response object expected from GraphHopper Leaflet Routing Machine
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getRoute(@DefaultValue("car") @QueryParam("vehicle") String vehicle,
                            @DefaultValue("") @QueryParam("avoid_area") String avoidArea,
                            @DefaultValue("") @QueryParam("waypoints") String waypoints) {

        _vehicle = vehicle;

        // 1: init GH
        DynamicGH hopper = initGH(_vehicle);
        // 2: extract barriers and apply them
        blockAreaSetup(hopper, avoidArea);
        // 3: extract waypoints
        String[] waypointsArray = waypoints.split(";");
        // 4: perform blocked routing
        GHResponse response = blockedRoute(hopper, waypointsArray);
        // 5: build response
        JSONObject jsonResponse = buildFormattedResponse(hopper, response);
        return Response.ok(jsonResponse.toString()).header("Access-Control-Allow-Origin", "*").build();

    }

    static String _vehicle = "car";
    final static String _algorithm = Parameters.Algorithms.DIJKSTRA_BI;

    public static void main(String[] args) {
    }

    public static DynamicGH initGH(String _vehicle) {
        // Create EncodingManager for the selected vehicle (car, foot, bike)
        final EncodingManager _vehicleManager = EncodingManager.create(_vehicle);
        // create one GraphHopper instance
        DynamicGH hopper = new DynamicGH();
        hopper.setDataReaderFile("data/tuscany/map_tuscany.osm");
        // where to store graphhopper files?
        hopper.setGraphHopperLocation("data/tuscany/map_tuscany_"+_vehicle+"-gh");
        //hopper.clean();
        hopper.setEncodingManager(_vehicleManager);
        hopper.setCHEnabled(false);

        // now this can take minutes if it imports or a few seconds for loading
        // of course this is dependent on the area you import
        hopper.importOrLoad();

        return hopper;
    }

    public static void blockAreaSetup(DynamicGH hopper, String avoidArea) {
        JSONObject jsonData = new JSONObject(avoidArea);

        GraphEdgeIdFinder.BlockArea blockArea =  new GraphEdgeIdFinder.BlockArea(hopper.getGraphHopperStorage());

        JSONArray features = jsonData.getJSONArray("features");
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);

            JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
            String type = feature.getJSONObject("geometry").getString("type");

            if( type.equals("Point") ) {
                blockArea.add(new Circle(coords.getDouble(1), coords.getDouble(0), 1));
            }
            if( type.equals("Polygon") ) {
                double[] lats = new double[coords.getJSONArray(0).length()];
                double[] lons = new double[coords.getJSONArray(0).length()];
                for (i = 0; i < coords.getJSONArray(0).length(); i++) {
                    lats[i] = coords.getJSONArray(0).getJSONArray(i).getDouble(1);
                    lons[i] = coords.getJSONArray(0).getJSONArray(i).getDouble(0);
                }
                blockArea.add(new Polygon(lats, lons));
            }
            if( type.equals("Point") && feature.getJSONObject("properties").has("radius") ) {      // circle
                double radius = feature.getJSONObject("properties").getDouble("radius");
                blockArea.add(new Circle(coords.getDouble(1), coords.getDouble(0), radius));
            }
        }
        hopper.setBlockArea(blockArea);
    }

    // build response json as required by leaflet routing machine
    public static JSONObject buildFormattedResponse(DynamicGH hopper, GHResponse rsp) {
        JSONObject jsonRsp = new JSONObject();

        // Use all paths
        List<PathWrapper> allPathsList = rsp.getAll();

        // --paths
        JSONArray pathArray = new JSONArray();
        for (PathWrapper path: allPathsList) {
            // get path geometry information (latitude, longitude and optionally elevation)
            PointList pointList = path.getPoints();
            // get information per turn instruction
            InstructionList il = path.getInstructions();
            // get time(milliseconds) and distance(meters) of the path
            double distance = path.getDistance();
            long millis = path.getTime();

            JSONObject paths = new JSONObject();
            // ----bbox
            BBox box = hopper.getGraphHopperStorage().getBounds();
            String bboxString = "["+box.minLon+","+box.minLat+","+box.maxLon+","+box.maxLat+"]";
            JSONArray bbox = new JSONArray(bboxString);
            paths.put("bbox", bbox);
            // ----points
            String encPoints = encodePolyline(pointList, false);
            paths.put("points", encPoints);
            // ----points_encoded
            paths.put("points_encoded", true);
            // ----time, distance
            paths.put("distance", distance);
            paths.put("time", millis);
            // ----instructions
            String instructionsString = new Gson().toJson(il.createJson());
            JSONArray instr = new JSONArray(instructionsString);
            paths.put("instructions", instr);

            pathArray.put(paths);
            jsonRsp.put("paths", pathArray);
        }

        // --info
        JSONObject info = new JSONObject();
        JSONArray copyrights = new JSONArray();
        copyrights.put("GraphHopper");
        copyrights.put("OpenStreetMap contributors");
        info.put("copyrights", copyrights);

        jsonRsp.put("info", info);

        return jsonRsp;
    }

    public static void simpleRoute(GraphHopper hopper, double latFrom, double lonFrom, double latTo, double lonTo) {
        System.out.println("Simple route...");

        GHRequest req = new GHRequest(latFrom, lonFrom, latTo, lonTo)
                .setWeighting("fastest")
                .setVehicle(_vehicle)
                .setLocale(Locale.ENGLISH)
                .setAlgorithm(_algorithm);

        GHResponse rsp = hopper.route(req);

        printResponseDetails(rsp);
    }

    public static void simpleRouteAlt(GraphHopper hopper, double latFrom, double lonFrom, double latTo, double lonTo) {
        System.out.println("Simple route with alternatives...");

        GHRequest req = new GHRequest(latFrom, lonFrom, latTo, lonTo)
                .setWeighting("fastest")
                .setVehicle(_vehicle)
                .setLocale(Locale.ENGLISH)
                .setAlgorithm(_algorithm);

        GHResponse rsp = hopper.route(req);


        printAlternativeDetails(rsp);
    }

    public static GHResponse blockedRoute(GraphHopper hopper, String[] waypointsArray) {
        System.out.println("\nBlocked route...");

        GHRequest req = new GHRequest();
        for (int i = 0; i < waypointsArray.length; i++) {
            double curLat = Double.parseDouble(waypointsArray[i].split(",")[1]);
            double curLon = Double.parseDouble(waypointsArray[i].split(",")[0]);

            req.addPoint(new GHPoint(curLat, curLon));
        }

        req.setWeighting("block_area")
            .setVehicle(_vehicle)
            .setLocale(Locale.ENGLISH);

        // GH does not allow alt routes with >2 waypoints, so we manage this case disabling alt route for >2 waypoints
        if(waypointsArray.length>2)
            req.setAlgorithm(_algorithm);
        else
            req.setAlgorithm(Parameters.Algorithms.ALT_ROUTE);

        return hopper.route(req);
    }

    public static void printResponseDetails(GHResponse rsp) {
        // first check for errors
        if(rsp.hasErrors()) {
            System.out.println("Response error: "+rsp.getErrors());
            return;
        }

        // use the best path, see the GHResponse class for more possibilities.
        PathWrapper path = rsp.getBest();

        // points, distance in meters and time in millis of the full path
        PointList pointList = path.getPoints();
        double distance = path.getDistance();
        long timeInMs = path.getTime();

        System.out.printf("Distance: %.2f km\n", distance/1000);
        System.out.printf("Time: %.2f min\n", (double)timeInMs/3600000);

        // translation
        TranslationMap trMap = new TranslationMap().doImport();
        Locale l = new Locale("en");
        Translation itTranslation = trMap.getWithFallBack(l);

        InstructionList il = path.getInstructions();
        // iterate over every turn instruction
        for(Instruction instruction : il) {
            System.out.println(instruction.getTurnDescription(itTranslation));
            //System.out.println(instruction.toString());
        }


    }

    public static void printAlternativeDetails(GHResponse rsp) {
        // first check for errors
        if(rsp.hasErrors()) {
            System.out.println("Response error: "+rsp.getErrors());
            return;
        }

        List<PathWrapper> paths = rsp.getAll();
        for (PathWrapper path: paths) {
            // points, distance in meters and time in millis of the full path
            PointList pointList = path.getPoints();
            double distance = path.getDistance();
            long timeInMs = path.getTime();

            System.out.printf("Distance: %.2f km\n", distance/1000);
            System.out.printf("Time: %.2f min\n", (double)timeInMs/3600000);

            // translation
            TranslationMap trMap = new TranslationMap().doImport();
            Locale l = new Locale("it");
            Translation itTranslation = trMap.getWithFallBack(l);

            InstructionList il = path.getInstructions();
            // iterate over every turn instruction
            for(Instruction instruction : il) {
                System.out.println(instruction.getTurnDescription(itTranslation));
                //System.out.println(instruction.toString());
            }
            System.out.println("------------------------------------");
        }
    }


    // Other utility methods (for developing)

    /**
     * Get closest node/edge from lat, long coords
     * oppure:
     *      LocationIndex locationindex = myHopper.getLocationIndex();
     *      QueryResult qr = locationindex.findClosest(latFrom, lonFrom, EdgeFilter.ALL_EDGES);
     */
    public static int getClosestNode(GraphHopper hopper, double lat, double lon) {
        QueryResult qr = hopper.getLocationIndex().findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        return qr.getClosestNode();
    }

    public static EdgeIteratorState getClosestEdge(GraphHopper hopper, double lat, double lon) {
        QueryResult qr = hopper.getLocationIndex().findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        return qr.getClosestEdge();
    }


    // reverse direction ----------------------
    // @see https://stackoverflow.com/questions/29851245/graphhopper-route-direction-weighting
    public static void printProps(GraphHopper hopper, double lat, double lon) {
        EdgeIteratorState edge = getClosestEdge(hopper, lat, lon);
        int baseNodeId = edge.getBaseNode();
        int adjNodeId = edge.getAdjNode();

        LocationIndex locationindex = hopper.getLocationIndex();
        QueryResult qr = locationindex.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        // come fare a assegnare un peso diverso ai due sensi di marcia di un edge (se presenti) ???
    }

    // @see https://stackoverflow.com/questions/29851245/graphhopper-route-direction-weighting
    public static boolean isReverseDirection(GraphHopper hopper, GHPoint target, GHPoint previous) {
        AngleCalc calc = new AngleCalc();
        // Input are two last points of vehicle. Base on two last points, I'm computing angle
        double angle = calc.calcOrientation(previous.lat, previous.lon, target.lat, target.lon);
        // Finding edge in place where is vehicle
        EdgeIteratorState edgeState = getClosestEdge(hopper, target.lat, target.lon);
        PointList pl = edgeState.fetchWayGeometry(3);
        // Computing angle of edge based on geometry
        double edgeAngle = calc.calcOrientation(pl.getLatitude(0), pl.getLongitude(0),
                pl.getLat(pl.size() - 1), pl.getLongitude(pl.size() - 1) );
        // Comparing two edges
        return (Math.abs(edgeAngle - angle) > 90 );
    }

    public static void printTest(GraphHopper hopper, GHResponse rsp) {
        // first check for errors
        if(rsp.hasErrors()) {
            System.out.println("Response error: "+rsp.getErrors());
            return;
        }

        // use the best path, see the GHResponse class for more possibilities.
        PathWrapper path = rsp.getBest();

        // points, distance in meters and time in millis of the full path
        PointList pointList = path.getPoints();

        System.out.println(pointList.toString()+"\n\n");

        for(int i = 0; i < pointList.size()-1; i++ ) {
            System.out.println("Da "+pointList.getLatitude(i)+","+pointList.getLongitude(i)+" A "+
                    pointList.getLatitude(i+1)+","+pointList.getLongitude(i+1)+
                    " --> "+ isReverseDirection(hopper, new GHPoint(pointList.getLatitude(i), pointList.getLongitude(i)),
                    new GHPoint(pointList.getLatitude(i+1), pointList.getLongitude(i+1)))+"\n");
        }

    }

    // polyline utilities

    public static PointList decodePolyline(String encoded, int initCap, boolean is3D) {
        PointList poly = new PointList(initCap, is3D);
        int index = 0;
        int len = encoded.length();
        int lat = 0, lng = 0, ele = 0;
        while (index < len) {
            // latitude
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int deltaLatitude = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += deltaLatitude;

            // longitute
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int deltaLongitude = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += deltaLongitude;

            if (is3D) {
                // elevation
                shift = 0;
                result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int deltaElevation = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                ele += deltaElevation;
                poly.add((double) lat / 1e5, (double) lng / 1e5, (double) ele / 100);
            } else
                poly.add((double) lat / 1e5, (double) lng / 1e5);
        }
        return poly;
    }

    public static String encodePolyline(PointList poly) {
        if (poly.isEmpty())
            return "";

        return encodePolyline(poly, poly.is3D());
    }

    public static String encodePolyline(PointList poly, boolean includeElevation) {
        return encodePolyline(poly, includeElevation, 1e5);
    }

    public static String encodePolyline(PointList poly, boolean includeElevation, double precision) {
        StringBuilder sb = new StringBuilder();
        int size = poly.getSize();
        int prevLat = 0;
        int prevLon = 0;
        int prevEle = 0;
        for (int i = 0; i < size; i++) {
            int num = (int) Math.floor(poly.getLatitude(i) * precision);
            encodeNumber(sb, num - prevLat);
            prevLat = num;
            num = (int) Math.floor(poly.getLongitude(i) * precision);
            encodeNumber(sb, num - prevLon);
            prevLon = num;
            if (includeElevation) {
                num = (int) Math.floor(poly.getElevation(i) * 100);
                encodeNumber(sb, num - prevEle);
                prevEle = num;
            }
        }
        return sb.toString();
    }

    private static void encodeNumber(StringBuilder sb, int num) {
        num = num << 1;
        if (num < 0) {
            num = ~num;
        }
        while (num >= 0x20) {
            int nextValue = (0x20 | (num & 0x1f)) + 63;
            sb.append((char) (nextValue));
            num >>= 5;
        }
        num += 63;
        sb.append((char) (num));
    }
}