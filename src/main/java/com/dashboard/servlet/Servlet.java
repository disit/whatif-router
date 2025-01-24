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

import com.graphhopper.*;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphEdgeIdFinder;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.Circle;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.Polygon;
import com.graphhopper.gtfs.*;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import java.time.ZoneId;
import java.io.File;
import com.graphhopper.config.Profile;

import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Path("/route")
public class Servlet {

    final static String _algorithm = Parameters.Algorithms.DIJKSTRA_BI;
    private static final String _graphLocation = "graph-cache";
    private static String _gtfsFile = "at.gtfs,gest.gtfs";
    private static String _datareaderFile = "centro-latest.osm.pbf";
    private static final ZoneId _zoneId = ZoneId.of("Europe/Rome");

    /**
     * API interface method called by Dashboard
     *
     * @param avoidArea FeatureCollection object (in GeoJSON format) containing
     * the areas to avoid in routing calculation
     * @param waypoints Routing lat/lng waypoints separated by ';'
     * @return the Response object expected from GraphHopper Leaflet Routing
     * Machine
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public static Response getRoute(@QueryParam("waypoints") String waypoints,
            @DefaultValue("car") @QueryParam("vehicle") String vehicle,
            @DefaultValue("") @QueryParam("avoid_area") String avoidArea,
            @DefaultValue("") @QueryParam("startDatetime") String startTimestamp,
            @DefaultValue("fastest") @QueryParam("weighting") String weighting,
            @DefaultValue("") @QueryParam("routing") String routing) {

        // If the startDatetime is not specified, use the current datetime
        LocalDateTime startDatetime;
        if (startTimestamp.isEmpty()) {
            startDatetime = LocalDateTime.now();
        } else {
            startDatetime = LocalDateTime.parse(startTimestamp);
        }

        String[] waypointsArray = waypoints.split(";");
        GraphHopper hopper;
        GHResponse response;

        if (routing.equals("pt")) {
            GraphHopperConfig config = createConfig();
            hopper = initGHGtfs(config);
            PtRouter ptRouter = initPtRouter(config, (GraphHopperGtfs) hopper);
            response = getGtfsRoute(ptRouter, waypointsArray, startDatetime);
        } else {
            hopper = initGH(vehicle, weighting, startDatetime);
            if (!avoidArea.isEmpty()) {
                blockAreaSetup((DynamicGraphHopper) hopper, avoidArea);  // extract barriers and apply them
            }
            response = blockedRoute(vehicle, hopper, waypointsArray);
        }

        JSONObject jsonResponse = buildFormattedResponse(routing, hopper, response);
        if (routing.equals("pt")) {
            hopper.close();
        }
        return Response.ok(jsonResponse.toString()).header("Access-Control-Allow-Origin", "*").build();
    }

    public static void main(String[] args) {
        // Uncomment the following lines to test the routing methods
//        getRoute("car",
//                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"radius\":148.31828400014956},\"geometry\":{\"type\":\"Point\",\"coordinates\":[11.268089,43.777663]}}],\"scenarioName\":\"gia pan - sce01\",\"isPublic\":false}",
//                "11.261587142944338,43.783860157932395;11.271286010742188,43.76582535876258",
//                "",
//                "fastest"
//        );
    }

    /**
     * Create a GraphHopperConfig instance with specified settings.
     *
     * This method sets up the GraphHopper configuration for public transit
     * routing addressing where to find General Transit Feed Specification
     * (GTFS) data and defining the profiles and routing preferences.
     *
     * @return GraphHopperConfig An instance of GraphHopperConfig with the
     * specified settings.
     *
     */
    public static GraphHopperConfig createConfig() {
        GraphHopperConfig ghConfig = new GraphHopperConfig();
        String mapPbf = System.getenv("GH_MAP_PBF");
        if (mapPbf == null) {
            mapPbf = _datareaderFile;
        }
        String ghLocationPfx = System.getenv("GH_LOCATION_PFX");
        if (ghLocationPfx == null) {
            ghLocationPfx = _graphLocation;
        }
        String ghGtfsFiles = System.getenv("GH_GTFS_FILES");
        if (ghGtfsFiles == null) {
            ghGtfsFiles = _gtfsFile;
        }

        ghConfig.putObject("datareader.file", mapPbf);
        ghConfig.putObject("import.osm.ignored_highways", "motorway, trunk");
        ghConfig.putObject("graph.location", ghLocationPfx);
        ghConfig.putObject("gtfs.file", ghGtfsFiles);
        ghConfig.setProfiles(Arrays.asList(
                new Profile("foot").setVehicle("foot").setWeighting("fastest")));
        return ghConfig;
    }

    /**
     * Initializes and configures an instance of PtRouter for calculating public
     * transport routes based on GraphHopper and GTFS data.
     *
     * This method creates a PtRouter object using the specified configuration
     * and components from GraphHopperGtfss.
     *
     * @param ghConfig GraphHopperConfig It contains the settings for
     * GraphHopper.
     * @param graphHopperGtfs GraphHopperGtfs An instance with the specified
     * settings.
     *
     * @return PtRouter An instance of PtRouter ready to calculate the routes.
     *
     */
    public static PtRouter initPtRouter(GraphHopperConfig ghConfig, GraphHopperGtfs graphHopperGtfs) {
        PtRouter ptRouter = new PtRouterImpl.Factory(ghConfig, new TranslationMap().doImport(), graphHopperGtfs.getBaseGraph(), graphHopperGtfs.getEncodingManager(), graphHopperGtfs.getLocationIndex(), graphHopperGtfs.getGtfsStorage())
                .createWithoutRealtimeFeed();
        return ptRouter;
    }

    /**
     * Initializes and configures a GraphHopperGTFS instance with specified
     * settings.
     *
     * This method sets up the GraphHopper configuration for public transit
     * routing using General Transit Feed Specification (GTFS) data.
     *
     * @param ghConfig The GraphHopperConfig instance used for instantiate
     * GraphHopper.
     *
     * @return GraphHopperGtfs An instance of GraphHopperGtfs configured with
     * the specified settings.
     *
     */
    public static GraphHopperGtfs initGHGtfs(GraphHopperConfig ghConfig) {
        GraphHopperGtfs graphHopperGtfs;
        graphHopperGtfs = new GraphHopperGtfs(ghConfig);
        graphHopperGtfs.init(ghConfig);
        graphHopperGtfs.importOrLoad();

        return graphHopperGtfs;
    }

    public static DynamicGraphHopper initGH(String _vehicle, String weighting, LocalDateTime startDatetime) {
        // Create EncodingManager for the selected vehicle (car, foot, bike)
        final EncodingManager vehicleManager = EncodingManager.create(_vehicle);

        // create one GraphHopper instance
        DynamicGraphHopper hopper = new DynamicGraphHopper(startDatetime);
        String mapPbf = System.getenv("GH_MAP_PBF");
        if (mapPbf == null) {
            mapPbf = _datareaderFile;
        }
        String ghLocationPfx = System.getenv("GH_LOCATION_PFX");
        if (ghLocationPfx == null) {
            ghLocationPfx = _graphLocation;
        }
        hopper.setOSMFile(mapPbf);
        hopper.setGraphHopperLocation(ghLocationPfx + "_" + _vehicle + "_" + weighting + "_map-gh"); // The location should be different for each Profile (vehicle + weighting)
        hopper.setProfiles(new Profile(_vehicle).setVehicle(_vehicle).setWeighting(weighting));

        // now this can take minutes if it imports or a few seconds for loading (of course this is dependent on the area you import)
        hopper.importOrLoad();
        return hopper;
    }

    /**
     * Calculates a public transit route using a GraphHopperGTFS instance and an
     * array of waypoints.
     *
     * This method constructs a route request based on the provided waypoints,
     * which are specified as latitude and longitude pairs. It utilizes the
     * specified PtRouterImpl instance to compute the transit route and returns
     * the response.
     *
     * @param ptRouter The PtRouter instance to be used for routing.
     * @param waypointsArray An array of strings representing waypoints, where
     * each string contains a longitude and latitude in the format
     * "longitude,latitude".
     * @param startDatetime The LocalDateTime that specifies the date in which
     * we want to calculate the trip.
     *
     * @return GHResponse The response containing the routing information,
     * including the calculated route and other related data.
     */
    public static GHResponse getGtfsRoute(PtRouter ptRouter, String[] waypointsArray, LocalDateTime startDatetime) {
        List<GHLocation> points = new ArrayList<>();
        for (String s : waypointsArray) {
            double curLat = Double.parseDouble(s.split(",")[1]);
            double curLon = Double.parseDouble(s.split(",")[0]);

            points.add(new GHPointLocation(new GHPoint(curLat, curLon)));
        }
        Request ghRequest = new Request(points, startDatetime.atZone(_zoneId).toInstant());
        return ptRouter.route(ghRequest);
    }

    public static void blockAreaSetup(DynamicGraphHopper hopper, String avoidArea) {

        JSONObject jsonData = new JSONObject(avoidArea);
        GraphEdgeIdFinder.BlockArea blockArea = new GraphEdgeIdFinder.BlockArea(hopper.getBaseGraph());

        JSONArray features = jsonData.getJSONArray("features");
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);

            JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
            String type = feature.getJSONObject("geometry").getString("type");

            if (type.equals("Point")) { // Circle or point
                double radius = 1;  // Point without radius is a circle with radius = 1
                if (feature.getJSONObject("properties").has("radius")) {  // Circle
                    radius = feature.getJSONObject("properties").getDouble("radius");
                }
                blockArea.add(new Circle(coords.getDouble(1), coords.getDouble(0), radius));
            }
            if (type.equals("Polygon")) {   // Polygon or BBox (rectangle)
                double[] lats = new double[coords.getJSONArray(0).length()];
                double[] lons = new double[coords.getJSONArray(0).length()];
                for (int coordinateIndex = 0; coordinateIndex < coords.getJSONArray(0).length(); coordinateIndex++) {
                    lats[coordinateIndex] = coords.getJSONArray(0).getJSONArray(coordinateIndex).getDouble(1);
                    lons[coordinateIndex] = coords.getJSONArray(0).getJSONArray(coordinateIndex).getDouble(0);
                }
                blockArea.add(new Polygon(lats, lons));
            }
        }
        hopper.setBlockArea(blockArea);
    }

    // build response json as required by leaflet routing machine
    public static JSONObject buildFormattedResponse(String _routingType, GraphHopper hopper, GHResponse response) {
        JSONObject jsonRsp = new JSONObject();

        // Use all paths
        List<ResponsePath> allPathsList = response.getAll();

        // paths
        JSONArray pathArray = new JSONArray();
        for (ResponsePath path : allPathsList) {
            // get path geometry information (latitude, longitude and optionally elevation)
            PointList pointList = path.getPoints();
            // get information per turn instruction
            InstructionList instructions = path.getInstructions();

            // get time(milliseconds) and distance(meters) of the path
            double distance = path.getDistance();
            long millis = path.getTime();

            JSONObject jsonPath = new JSONObject();

            jsonPath.put("wkt", pointList.toLineString(false));

            // bbox
            BBox box = hopper.getBaseGraph().getBounds();
            String bboxString = "[" + box.minLon + "," + box.minLat + "," + box.maxLon + "," + box.maxLat + "]";
            JSONArray bbox = new JSONArray(bboxString);
            jsonPath.put("bbox", bbox);

            // points
            String encPoints = encodePolyline(pointList, false);
            jsonPath.put("points", encPoints);

            // points_encoded
            jsonPath.put("points_encoded", true);

            // time, distance
            jsonPath.put("distance", distance);
            jsonPath.put("time", millis);

            // instructions
            if (_routingType.equals("pt")) {
                List<Trip.Leg> legs = path.getLegs();
                jsonPath.put("instructions", new JSONArray(serializeLegInstructions((GraphHopperGtfs) hopper, instructions, legs)));
            } else {
                jsonPath.put("instructions", new JSONArray(serializeInstructions(instructions)));
            }

            pathArray.put(jsonPath);   // Add the path to the array of paths
        }

        jsonRsp.put("paths", pathArray);

        // --info
        JSONObject info = new JSONObject();
        JSONArray copyrights = new JSONArray();
        copyrights.put("GraphHopper");
        copyrights.put("OpenStreetMap contributors");
        info.put("copyrights", copyrights);

        jsonRsp.put("info", info);

        return jsonRsp;
    }

    /**
     * Serializes a list of Trip legs into a JSON object representation.
     *
     * This method takes a list of legs from a Trip and converts them into a
     * JSON object. It specifically handles legs of type Trip.PtLeg, extracting
     * relevant information such as trip ID, route ID, feed ID, travel time,
     * type, trip headsign, and associated stops.
     *
     * @param graphHopperGtfs An instance from which retrieve generic
     * informations.
     * @param legs A list of Trip.Leg objects to be serialized. The method
     * currently processes only instances of Trip.PtLeg.
     *
     * @return JSONObject A JSON object containing serialized information about
     * the legs, including trip and stop details.
     */
    public static JSONArray serializeLegs(GraphHopperGtfs graphHopperGtfs, List<Trip.Leg> legs) {
        JSONArray legObjectList = new JSONArray();

        for (Trip.Leg leg : legs) {
            JSONObject legObject = new JSONObject();
            if (leg instanceof Trip.PtLeg) {
                Trip.PtLeg ptLeg = (Trip.PtLeg) leg;
                JSONArray stopList = new JSONArray();
                legObject.put("trip_id", ptLeg.trip_id);
                String route_id = ptLeg.route_id;
                legObject.put("route_id", ptLeg.route_id);
                String feed_id = ptLeg.feed_id;
                legObject.put("feed_id", ptLeg.feed_id);
                legObject.put("travelTime", ptLeg.travelTime);
                legObject.put("type", ptLeg.type);
                legObject.put("trip_headsign", ptLeg.trip_headsign);
                legObject.put("trip_id", ptLeg.trip_id);
                List<Trip.Stop> stops = ptLeg.stops;

                String agencyId = graphHopperGtfs.getGtfsStorage().getGtfsFeeds().get(feed_id).routes.get(route_id).agency_id;
                String agencyName = graphHopperGtfs.getGtfsStorage().getGtfsFeeds().get(feed_id).agency.get(agencyId).agency_name;
                String routeShortName = graphHopperGtfs.getGtfsStorage().getGtfsFeeds().get(feed_id).routes.get(route_id).route_short_name;
                legObject.put("agency_id", agencyId);
                legObject.put("agency_name", agencyName);
                legObject.put("route_name", routeShortName);

                for (Iterator<Trip.Stop> stop_iterator = stops.iterator(); stop_iterator.hasNext();) {
                    JSONObject stopJson = new JSONObject();
                    Trip.Stop stop = stop_iterator.next();
                    stopList.put(stopJson);
                    stopJson.put("stop_id", stop.stop_id);
                    stopJson.put("stop_name", stop.stop_name);
                    if (stop.arrivalTime != null) {
                        ZonedDateTime zat = stop.arrivalTime.toInstant().atZone(ZoneId.systemDefault());
                        stopJson.put("stop_arrivalTime", zat.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    } else {
                        ZonedDateTime zdt = stop.departureTime.toInstant().atZone(ZoneId.systemDefault());
                        stopJson.put("stop_arrivalTime", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                }
                legObject.put("stop", stopList);
                legObjectList.put(legObject);
            }

        }

        return legObjectList;
    }

    /**
     * Serializes a list of instructions containing legs into a JSON object
     * representation.
     *
     * This method takes a list of instruction and legs converting them into a
     * JSON object.
     *
     * @param graphHopperGtfs An instance from which retrieve generic
     * informations.
     * @param legs A list of Trip.Leg objects to be serialized. The method
     * currently processes only instances of Trip.PtLeg.
     *
     * @return String A string containing all the information about the response
     * returned from the GraphHopperGtfs routing to insert them into a
     * JSONObject
     */
    public static String serializeLegInstructions(GraphHopperGtfs graphHopperGtfs, InstructionList instructions, List<Trip.Leg> legs) {

        List<Map<String, Object>> instrList = new ArrayList<>(instructions.size());
        int pointsIndex = 0;
        JSONObject leg;

        int tmpIndex;
        int legsCounter = 0;
        JSONArray jsonLegs = serializeLegs(graphHopperGtfs, legs);
        for (Iterator<Instruction> iterator = instructions.iterator(); iterator.hasNext(); pointsIndex = tmpIndex) {
            Instruction instruction = iterator.next();
            Map<String, Object> instrJson = new HashMap<>();
            instrList.add(instrJson);

            if (Helper.firstBig(instruction.getTurnDescription(instructions.getTr())).equals("Pt_start_trip")) {
                instrJson.put("text", "Pt_start_trip");
                leg = jsonLegs.getJSONObject(legsCounter);
                instrJson.put("leg", leg);
                legsCounter++;
            } else {
                instrJson.put("text", Helper.firstBig(instruction.getTurnDescription(instructions.getTr())));
                instrJson.put("street_name", instruction.getName());
                instrJson.put("time", instruction.getTime());
                instrJson.put("distance", Helper.round(instruction.getDistance(), 3));
                instrJson.put("sign", instruction.getSign());
                instrJson.putAll(instruction.getExtraInfoJSON());
            }

            tmpIndex = pointsIndex + instruction.getLength();
            instrJson.put("interval", Arrays.asList(pointsIndex, tmpIndex));
        }

        return new Gson().toJson(instrList.toArray());
    }

    /**
     * Perform a simple route calculation and print the best path details
     *
     * @param hopper GraphHopper instance
     * @param latFrom Start latitude
     * @param lonFrom Start longitude
     * @param latTo End latitude
     * @param lonTo End longitude
     */
    public static void simpleRoute(String _vehicle, GraphHopper hopper, double latFrom, double lonFrom, double latTo, double lonTo) {
        System.out.println("Simple route...");

        GHRequest req = new GHRequest(latFrom, lonFrom, latTo, lonTo)
                .setProfile(_vehicle)
                .setLocale(Locale.ENGLISH)
                .setAlgorithm(_algorithm);

        GHResponse rsp = hopper.route(req);
        printResponseDetails(rsp);
    }

    /**
     * Perform a simple route calculation with alternatives and print all paths
     *
     * @param hopper GraphHopper instance
     * @param latFrom Start latitude
     * @param lonFrom Start longitude
     * @param latTo End latitude
     * @param lonTo End longitude
     */
    public static void simpleRouteAlt(String _vehicle, GraphHopper hopper, double latFrom, double lonFrom, double latTo, double lonTo) {
        System.out.println("Simple route with alternatives...");

        GHRequest req = new GHRequest(latFrom, lonFrom, latTo, lonTo)
                .setProfile(_vehicle)
                .setLocale(Locale.ENGLISH)
                .setAlgorithm(_algorithm);

        GHResponse rsp = hopper.route(req);
        printAlternativeDetails(rsp);
    }

    /**
     * Perform a route calculation and print the best path details
     *
     * @param hopper GraphHopper instance (could have a blockArea set)
     * @param waypointsArray Array of waypoints (lat, lon)
     */
    public static GHResponse blockedRoute(String _vehicle,GraphHopper hopper, String[] waypointsArray) {
        System.out.println("Blocked route...");

        GHRequest req = new GHRequest();
        for (String s : waypointsArray) {
            double curLat = Double.parseDouble(s.split(",")[1]);
            double curLon = Double.parseDouble(s.split(",")[0]);

            req.addPoint(new GHPoint(curLat, curLon));
        }

        req.setProfile(_vehicle).setLocale(Locale.ENGLISH);

        // GH does not allow alt routes with > 2 waypoints, so we manage this case disabling alt route for >2 waypoints
        if (waypointsArray.length > 2) {
            req.setAlgorithm(_algorithm);
        } else {
            req.setAlgorithm(Parameters.Algorithms.ALT_ROUTE);
        }

        return hopper.route(req);
    }

    public static void printResponseDetails(GHResponse rsp) {
        // first check for errors
        if (rsp.hasErrors()) {
            System.out.println("Response error: " + rsp.getErrors());
            return;
        }

        // use the best path, see the GHResponse class for more possibilities.
        ResponsePath path = rsp.getBest();

        // points, distance in meters and time in millis of the full path
        PointList pointList = path.getPoints();
        double distance = path.getDistance();
        long timeInMs = path.getTime();

        System.out.printf("Distance: %.2f km\n", distance / 1000);
        System.out.printf("Time: %.2f min\n", (double) timeInMs / 3600000);

        // translation
        TranslationMap trMap = new TranslationMap().doImport();
        Translation itTranslation = trMap.getWithFallBack(Locale.ENGLISH);

        InstructionList il = path.getInstructions();
        // iterate over every turn instruction
        for (Instruction instruction : il) {
            System.out.println(instruction.getTurnDescription(itTranslation));
            //System.out.println(instruction.toString());
        }
    }

    public static void printAlternativeDetails(GHResponse rsp) {
        // first check for errors
        if (rsp.hasErrors()) {
            System.out.println("Response error: " + rsp.getErrors());
            return;
        }

        List<ResponsePath> paths = rsp.getAll();
        for (ResponsePath path : paths) {
            // points, distance in meters and time in millis of the full path
            // PointList pointList = path.getPoints();
            double distance = path.getDistance();
            long timeInMs = path.getTime();

            System.out.printf("Distance: %.2f km\n", distance / 1000);
            System.out.printf("Time: %.2f min\n", (double) timeInMs / 3600000);

            // translation
            TranslationMap trMap = new TranslationMap().doImport();
            Translation itTranslation = trMap.getWithFallBack(Locale.ENGLISH);

            InstructionList il = path.getInstructions();
            // iterate over every turn instruction
            for (Instruction instruction : il) {
                System.out.println(instruction.getTurnDescription(itTranslation));
                //System.out.println(instruction.toString());
            }
            System.out.println("------------------------------------");
        }
    }

    public static String serializeInstructions(InstructionList instructions) {

        List<Map<String, Object>> instrList = new ArrayList<>(instructions.size());
        int pointsIndex = 0;

        int tmpIndex;
        for (Iterator<Instruction> iterator = instructions.iterator(); iterator.hasNext(); pointsIndex = tmpIndex) {
            Instruction instruction = iterator.next();
            Map<String, Object> instrJson = new HashMap<>();
            instrList.add(instrJson);
            instrJson.put("text", Helper.firstBig(instruction.getTurnDescription(instructions.getTr())));
            instrJson.put("street_name", instruction.getName());
            instrJson.put("time", instruction.getTime());
            instrJson.put("distance", Helper.round(instruction.getDistance(), 3));
            instrJson.put("sign", instruction.getSign());
            instrJson.putAll(instruction.getExtraInfoJSON());
            tmpIndex = pointsIndex + instruction.getLength();
            instrJson.put("interval", Arrays.asList(pointsIndex, tmpIndex));
        }

        return new Gson().toJson(instrList.toArray());
    }

    // --------------------------------------
    // Other utility methods (for developing)
    // --------------------------------------
    /**
     * Get the closest node/edge from lat, long coordinates
     *
     * @param hopper GraphHopper instance
     * @param lat latitude of the point
     * @param lon longitude of the point
     */
    public static int getClosestNode(GraphHopper hopper, double lat, double lon) {
        Snap qr = hopper.getLocationIndex().findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        return qr.getClosestNode();
    }

    /**
     * Get the closest edge from lat, long coordinates
     *
     * @param hopper GraphHopper instance
     * @param lat latitude of the point
     * @param lon longitude of the point
     */
    public static EdgeIteratorState getClosestEdge(GraphHopper hopper, double lat, double lon) {
        Snap qr = hopper.getLocationIndex().findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        return qr.getClosestEdge();
    }

    // reverse direction ----------------------
    // @see https://stackoverflow.com/questions/29851245/graphhopper-route-direction-weighting
    public static void printProps(GraphHopper hopper, double lat, double lon) {
        EdgeIteratorState edge = getClosestEdge(hopper, lat, lon);
        int baseNodeId = edge.getBaseNode();
        int adjNodeId = edge.getAdjNode();

        LocationIndex locationindex = hopper.getLocationIndex();
        Snap qr = locationindex.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        // come fare a assegnare un peso diverso ai due sensi di marcia di un edge (se presenti) ???
    }

    // @see https://stackoverflow.com/questions/29851245/graphhopper-route-direction-weighting
    public static boolean isReverseDirection(GraphHopper hopper, GHPoint target, GHPoint previous) {
        AngleCalc calc = new AngleCalc();
        // Input are two last points of vehicle. Base on two last points, I'm computing angle
        double angle = calc.calcOrientation(previous.lat, previous.lon, target.lat, target.lon);
        // Finding edge in place where is vehicle
        EdgeIteratorState edgeState = getClosestEdge(hopper, target.lat, target.lon);
        PointList pl = edgeState.fetchWayGeometry(FetchMode.ALL);
        // Computing angle of edge based on geometry
        double edgeAngle = calc.calcOrientation(pl.getLat(0), pl.getLon(0),
                pl.getLat(pl.size() - 1), pl.getLon(pl.size() - 1));
        // Comparing two edges
        return (Math.abs(edgeAngle - angle) > 90);
    }

    public static void printTest(GraphHopper hopper, GHResponse rsp) {
        // first check for errors
        if (rsp.hasErrors()) {
            System.out.println("Response error: " + rsp.getErrors());
            return;
        }

        // use the best path, see the GHResponse class for more possibilities.
        ResponsePath path = rsp.getBest();

        // points, distance in meters and time in millis of the full path
        PointList pointList = path.getPoints();

        System.out.println(pointList.toString() + "\n\n");

        for (int i = 0; i < pointList.size() - 1; i++) {
            System.out.println("Da " + pointList.getLat(i) + "," + pointList.getLon(i) + " A "
                    + pointList.getLat(i + 1) + "," + pointList.getLon(i + 1)
                    + " --> " + isReverseDirection(hopper, new GHPoint(pointList.getLat(i), pointList.getLon(i)),
                            new GHPoint(pointList.getLat(i + 1), pointList.getLon(i + 1))) + "\n");
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

            // longitude
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
            } else {
                poly.add((double) lat / 1e5, (double) lng / 1e5);
            }
        }
        return poly;
    }

    public static String encodePolyline(PointList poly) {
        if (poly.isEmpty()) {
            return "";
        }
        return encodePolyline(poly, poly.is3D());
    }

    public static String encodePolyline(PointList poly, boolean includeElevation) {
        return encodePolyline(poly, includeElevation, 1e5);
    }

    public static String encodePolyline(PointList poly, boolean includeElevation, double precision) {
        StringBuilder sb = new StringBuilder();
        int size = poly.size();
        int prevLat = 0;
        int prevLon = 0;
        int prevEle = 0;
        for (int i = 0; i < size; i++) {
            int num = (int) Math.floor(poly.getLat(i) * precision);
            encodeNumber(sb, num - prevLat);
            prevLat = num;
            num = (int) Math.floor(poly.getLon(i) * precision);
            encodeNumber(sb, num - prevLon);
            prevLon = num;
            if (includeElevation) {
                num = (int) Math.floor(poly.getEle(i) * 100);
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
