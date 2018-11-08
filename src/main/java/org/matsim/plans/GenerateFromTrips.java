package org.matsim.plans;

import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.geom.impl.CoordinateArraySequence;
import com.vividsolutions.jts.triangulate.VoronoiDiagramBuilder;
import org.geotools.data.shapefile.shp.ShapeType;
import org.geotools.data.shapefile.shp.ShapefileWriter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;


import java.io.*;
import java.util.*;
import java.util.List;

public class GenerateFromTrips {
    private static boolean glueTripsTogether = true;
    private static HashMap<Stop, Polygon> stopToCell;
    private static boolean createAdditionalAgents = true;
    private static String fileSHP = "input/inputForPlans/agent_distritcts/agent districts.shp";
    private static Map<Integer, MultiPolygon> multiPolygonsMap;
    private static boolean generateOnLinks = true;
    private static String networkInputFile = "scenarios/zsd/networkEdit/network_spb_zsd_newcapasity_after_5.xml";


    public static void main(String[] args) throws FactoryException, TransformException {
        String inputCRS = "EPSG:4326"; // WGS84
        String outputCRS = "EPSG:32635";
        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(inputCRS, outputCRS);
        String inputStations = "input/inputForPlans/tripsFromValidations/stops.csv";
        String inputTrips = "input/inputForPlans/tripsFromValidations/TRIPS.csv";

        Map stopMap = new HashMap();
        List stopList = new ArrayList<Stop>();

        Map<String, Passenger> passengerMap = new HashMap();

        readStations(inputStations, stopMap, stopList, ct);
        readTrips(inputTrips, passengerMap);
        createPopulation(passengerMap, ct, stopMap, stopList);

    }

    private static void createPopulation(Map<String, Passenger> passengerMap, CoordinateTransformation ct, Map stopMap, List stopList) throws FactoryException, TransformException {
        int personsWithEmptyPlans = 0;
        int personsWithLegsOnEnd = 0;
        int removedPersonsWithAnomalyInLegNumber = 0;
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkInputFile);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Population population = scenario.getPopulation();
        PopulationFactory populationFactory = population.getFactory();
        Iterator iterator = passengerMap.values().iterator();
        while (iterator.hasNext()){
            Passenger passenger = (Passenger) iterator.next();
            Person person = populationFactory.createPerson(Id.createPersonId(passenger.getPassengerId()));
            Plan plan = populationFactory.createPlan();
            person.addPlan(plan);
            if (passenger.tripList.size() > 0){
                population.addPerson(person);
            }
            Iterator tripIterator = passenger.tripList.iterator();
            int tripIndex = 0;
            while (tripIterator.hasNext()){
                tripIndex++;
                Trip trip = (Trip) tripIterator.next();
                if ( !(stopMap.get(trip.getStartStopId())== null)){
                    Stop startStop = (Stop) stopMap.get(trip.getStartStopId());
                    Coord transformedCoord = ct.transform(startStop.getCoord());
                    String activityType;
                    boolean isLastActivity = false;
                    if (!tripIterator.hasNext()){
                        isLastActivity = true;
                    }
                    activityType = determineActivityType(passenger, tripIndex, trip);
                    Activity activity;
                    boolean isMetro = startStop.modes.contains("metro");
                    activity = getActivity(scenario, populationFactory, transformedCoord, activityType, isMetro);
                    activity.setEndTime(trip.startTime);
                    plan.addActivity(activity);
                    if (!isLastActivity){
                        addLegToPlan(populationFactory, plan);
                    } else if ((passenger.tripList.size() > 1) && tripIndex > 1){
                        Activity firstActivity = (Activity) person.getPlans().get(0).getPlanElements().get(0);
                        Activity lastActivity = getActivity(scenario, populationFactory, scenario.getNetwork().getLinks().get(firstActivity.getLinkId()).getCoord(), firstActivity.getType(), false);
                        lastActivity.setEndTime(25 * 3600);
                        addLegToPlan(populationFactory, plan);

                        plan.addActivity(lastActivity);
                        } else {
                            if (!(stopMap.get(trip.endStopId) == null)){
                                Stop endStop = (Stop) stopMap.get(trip.endStopId);
                                addHomeActivityAtEndStop(ct, populationFactory, plan, endStop, scenario);
                            } else {
                                Double randomStopIndexDouble = (stopList.size() * Math.random() - 0.5);
                                int randomStopIndex = randomStopIndexDouble.intValue();
                                Stop endStop = (Stop) stopList.get(randomStopIndex);
                                addHomeActivityAtEndStop(ct, populationFactory, plan, endStop, scenario);
                            }
                        }
                    }

                }



                if (((plan.getPlanElements().size() - 1) / 2) < tripIndex){
                    population.removePerson(person.getId());
                    removedPersonsWithAnomalyInLegNumber++;
            }




        }
        System.out.println("Removed " + removedPersonsWithAnomalyInLegNumber + " persons with anomaly in leg number");
        System.out.println("Removed persons with empty plans: " + personsWithEmptyPlans);
        System.out.println("Removed persons with legs on end: " + personsWithLegsOnEnd);

        deleteOrFixFaultyPlans(population);

        if (createAdditionalAgents){
            ParserSHP parserSHP = new ParserSHP();
            try {
                multiPolygonsMap = parserSHP.run(fileSHP);
            } catch (IOException e) {
                e.printStackTrace();
            }

            /*
            //GenerateInPolygons generateInPolygons = new GenerateInPolygons();
            //generateInPolygons.cloneAgentsFromPolygons(scenario, 4000, multiPolygonsMap.get(3), new Coord(674991,6655560));
            GenerateInPolygons generateInPolygons = new GenerateInPolygons();
            generateInPolygons.cloneAgentsFromPolygons(scenario, 2500, multiPolygonsMap.get(2));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 4000, multiPolygonsMap.get(3));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 4000, multiPolygonsMap.get(11), new Coord(666652, 6671643));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 1500, multiPolygonsMap.get(12), new Coord(676550, 6632331));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 2000, multiPolygonsMap.get(11), new Coord(665162, 6665387));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 5000, multiPolygonsMap.get(11), new Coord(684957, 6683798));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 3000, multiPolygonsMap.get(14), new Coord(684229, 6633293));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 7000, multiPolygonsMap.get(13), new Coord(678537, 6611645));
            generateInPolygons.cloneAgentsFromPolygons(scenario, 2500, multiPolygonsMap.get(12), new Coord(667140,6640067));
*/
        }
        deleteOrFixFaultyPlans(population);
        PopulationWriter populationWriter = new PopulationWriter(population);
        populationWriter.writeV5("output/PopulationOnLinks.xml");
    }

    private static Activity getActivity(Scenario scenario, PopulationFactory populationFactory, Coord transformedCoord, String activityType, boolean isMetro) {
        Activity activity;
        if (generateOnLinks){
            Coord randomizedTransformedCoord = randomizeCoord(transformedCoord, isMetro);
            Link targetLink = scenario.getNetwork().getLinks().values().stream().sorted((a, b) -> (int)  Math.abs((CoordUtils.calcEuclideanDistance(a.getCoord(), randomizedTransformedCoord) -
                    CoordUtils.calcEuclideanDistance(b.getCoord(), randomizedTransformedCoord)))).filter(link -> link.getFreespeed() < 70 / 3.6).findFirst().get();
            activity = populationFactory.createActivityFromLinkId(activityType, targetLink.getId());
        } else {
            Coord randomizedTransformedCoord = randomizeCoord(transformedCoord, isMetro);
        activity = populationFactory.
                createActivityFromCoord(activityType, randomizedTransformedCoord);
        }
        return activity;
    }



    private static void addHomeActivityAtEndStop(CoordinateTransformation ct, PopulationFactory populationFactory, Plan plan, Stop endStop, Scenario scenario) {
        Coord endStopCoord = endStop.getCoord();
        boolean isMetro = endStop.modes.contains("metro");
        Coord transformedEndStopCoord = ct.transform(endStopCoord);
        Activity lastActivity = getActivity(scenario, populationFactory, transformedEndStopCoord, "h", isMetro);
        lastActivity.setEndTime(25*3600);
        addLegToPlan(populationFactory, plan);
        plan.addActivity(lastActivity);
    }

    private static void deleteOrFixFaultyPlans(Population population) {
        int removedFaultyPlans = 0;
        int removedEmptyPlans = 0;
        int fixedFaultyPlans = 0;
        int removedPlansWithLegOnTheEnd = 0;
        for (Person person : new ArrayList<Person>(population.getPersons().values())){
            Plan plan = person.getPlans().get(0);
            if (plan.getPlanElements().isEmpty()) {
                population.removePerson(person.getId());
                removedEmptyPlans++;
            } else if ((plan.getPlanElements().get(plan.getPlanElements().size() - 1)) instanceof Leg){
                    population.removePerson(person.getId());
                removedPlansWithLegOnTheEnd++;
                } else {
                Iterator iterator = plan.getPlanElements().iterator();
                while (iterator.hasNext()){
                    PlanElement planElement = (PlanElement) iterator.next();
                    if (planElement instanceof Activity){
                        if (((Activity) planElement).getEndTime() < 0){
                            population.removePerson(person.getId());
                            removedFaultyPlans++;
                        }
                    }
                }

                Activity lastActivity = (Activity) plan.getPlanElements().get(plan.getPlanElements().size()- 1);
                Double endTimeOfLAstActivity = lastActivity.getEndTime();
                if (endTimeOfLAstActivity == null){
                    lastActivity.setEndTime(25 * 3600);
                    fixedFaultyPlans++;
                }
            }
        }
        System.out.println("removed " + removedEmptyPlans + " empty plans");
        System.out.println("removed " + removedPlansWithLegOnTheEnd + " plans with leg on the end");
        System.out.println("removed " + removedFaultyPlans + " faulty plans");
        System.out.println("fixed " + fixedFaultyPlans + " faulty plans");
    }

    private static void addLegToPlan(PopulationFactory populationFactory, Plan plan) {
        Leg leg = populationFactory.createLeg("car");
        plan.addLeg(leg);
    }

    private static String determineActivityType(Passenger passenger, int tripIndex, Trip trip) {
        String activityType;
        if ((tripIndex == 1) || (tripIndex > passenger.tripList.size())){
            activityType = "h";
        } else {
            if (trip.getStartTime() < 8 * 3600) {
                double random = Math.random();
                if (random < 0.95) {
                    activityType = "h";
                } else activityType = "w";
            } else if (trip.getStartTime() < 14 * 3600){
                double random = Math.random();
                if (random < 0.5) {
                    activityType = "e";
                } else activityType = "w";
            } else if (trip.getStartTime() < 20 * 3600){
                double random = Math.random();
                if (random < 0.2) {
                    activityType = "e";
                } else if (random < 0.4){
                    activityType = "s";
                } else activityType = "w";
            } else {
                double random = Math.random();
                if (random < 0.2) {
                    activityType = "w";
                } else activityType = "s";
            }
        }
        return activityType;
    }

    private static Coord randomizeCoord(Coord transformedCoord, boolean isMetro) {

        //Generates points in circles around the stops. Bigger circles for metro stations
        double accessDistance;
        if (isMetro){
            accessDistance = 22 * 60 * (5 / 3.6);
        } else accessDistance = 7.5 * 60 * (5 / 3.6);
        double phi = Math.random() * 2 * Math.PI;
        double radius = accessDistance * Math.sqrt(Math.random());
        double newX = transformedCoord.getX() + radius * Math.cos(phi);
        double newY = transformedCoord.getY() + radius * Math.sin(phi);
        Coord coord = CoordUtils.createCoord(newX, newY);
        return coord;
    }

    private static void readTrips(String inputTrips, Map passengerMap) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(inputTrips));
            String line = null;
            int lineNumber = 0;
            while ((line = bufferedReader.readLine()) != null){
                lineNumber++;
                String[] items = line.split(",");
                String cardId = items[0];
                Trip trip = new Trip(lineNumber, cardId);
                String[] startTimeRaw = items[1].split(":");
                int startHour = Integer.parseInt(startTimeRaw[0]);
                int startMinute = Integer.parseInt(startTimeRaw[1]);
                double startTime = startHour * 3600 + startMinute * 60 - (Math.random() * 10 * 60) - (5 * 60 + (Math.random() * 5 * 60));
                String startStopId = items[2];
                String endStopId = items[4];
                trip.setStartStopId(startStopId);
                trip.setStartTime(startTime);
                trip.setEndStopId(endStopId);


                if (passengerMap.containsKey(cardId)){
                    Passenger passenger = (Passenger) passengerMap.get(cardId);
                    passenger.tripList.add(trip);
                } else {
                    Passenger newPassenger = new Passenger(cardId);
                    newPassenger.tripList.add(trip);
                    passengerMap.put(newPassenger.getPassengerId(), newPassenger);
                }
            }

            sortPassengerTrips(passengerMap);
            System.out.println("successfully read the file with trips");
            if (glueTripsTogether){
                glueTrips(passengerMap);
            }
            cleanNullStops(passengerMap);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } ;
    }

    private static void glueTrips(Map passengerMap) {
        int gluedTrips = 0;
        Iterator iterator = passengerMap.keySet().iterator();
        while (iterator.hasNext()){
            Passenger passenger = (Passenger) passengerMap.get(iterator.next());
            if (passenger.tripList.size() > 1){
                for (int i = 0; i < passenger.tripList.size() - 2; i++){
                    Trip currentTrip = (Trip) passenger.tripList.get(i);
                    Trip nextTrip = (Trip) passenger.tripList.get(i+1);
                    if (nextTrip.getStartTime() - currentTrip.getStartTime() < 25 * 60) {
                        passenger.tripList.remove(i + 1);
                        gluedTrips++;
                        System.out.println("Trips glued together, because second of them starts less than 25 minutes after the first one");
                    }
                }
            }
        }
        System.out.println(gluedTrips + " trips are glued%%%%%%%%%%%%%%%");
    }

    private static void cleanNullStops(Map passengerMap) {
        Iterator iterator = passengerMap.keySet().iterator();
        while (iterator.hasNext()){
            String passengerId = (String) iterator.next();
            Passenger passenger = (Passenger) passengerMap.get(passengerId);
            Iterator tripIterator = passenger.tripList.iterator();
            int nextTripNumber = 0;
            while (tripIterator.hasNext()){
                nextTripNumber++;
                Trip trip = (Trip) tripIterator.next();
                if (trip.getEndStopId().equals("null")) {
                    if (nextTripNumber >= passenger.tripList.size()){
                        nextTripNumber = 0;
                    }
                    Trip nextTrip = (Trip) passenger.tripList.get(nextTripNumber);
                    trip.setEndStopId(nextTrip.startStopId);
                }
                if (trip.getEndStopId().equals(trip.getStartStopId())){
                    tripIterator.remove();
                }
            }
         }
         System.out.println("cleaned trips");
    }

    private static void sortPassengerTrips(Map passengerMap) {
        Iterator iterator = passengerMap.keySet().iterator();
        while (iterator.hasNext()){
            Passenger passenger = (Passenger) passengerMap.get(iterator.next());
            Collections.sort(passenger.tripList);
        }
        System.out.println("sorted!");
    }

    private static void readStations(String inputStations, Map stopMap, List stopList, CoordinateTransformation ct) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(inputStations));
            String line = null;
            int lineNumber = 0;

            while((line = bufferedReader.readLine()) != null) {
                lineNumber++;
                String[] items = line.split(",");

                String stopId = items[0];
                String stopName = items[2];
                String mode = items[3];
                Double y = Double.parseDouble(items[4]);
                Double x = Double.parseDouble(items[5]);
                Coord stopCoord = new Coord(x, y);
                Stop stop = new Stop(stopId, stopName, mode, stopCoord);
                stopMap.put(stopId, stop);
                stopList.add(stop);
            }

            System.out.println("end");

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
