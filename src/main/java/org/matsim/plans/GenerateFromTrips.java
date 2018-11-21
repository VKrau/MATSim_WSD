package org.matsim.plans;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;


import java.io.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class GenerateFromTrips {
    private static boolean glueTripsTogether = true;
    private static boolean createAdditionalAgents = true;
    private static HashMap<Id<Link>, HashSet<Agent>> mapOfAgentsOnLinks = new HashMap<>();
    private static boolean generateOnLinks = true;
    private static boolean recordWithValidationPopulation = true;
    //Изначальная дистанция поиска
    private static int initialSearchDistanceOfNearestNodes = 1000;
    //Если необходимое количество агентов не найдено, то шаг увеличения дистанции
    private static int searchExpansionStep = 500;
    //Сколько ближайших агентов необходимо найти
    private static int numberOfAgentsForSelectionPlan = 10;
    private static String networkInputFile = "scenarios/zsd/network_spb_zsd_newcapasity_after_5.xml";
    private static HashMap<String, Agent> mapOfAllAgents = new HashMap<>();
    private static HashMap<String, Agent> mapOfCreatedAgents = new HashMap<>();
    private static String filePopulationStatistics = "input/inputForPlans/tripsFromValidations/cik_final.csv";


    public static void main(String[] args) throws FactoryException, TransformException {
        String inputCRS = "EPSG:4326"; // WGS84
        String outputCRS = "EPSG:32635";
        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(inputCRS, outputCRS);
        String fileStations = "input/inputForPlans/tripsFromValidations/stops.csv";
        String fileTrips = "input/inputForPlans/tripsFromValidations/TRIPS.csv";

        Map stopMap = new HashMap();
        List stopList = new ArrayList<Stop>();

        Map<String, Passenger> passengerMap = new HashMap();
        readStations(fileStations, stopMap, stopList);
        readTrips(fileTrips, passengerMap);

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
        int abc = 0;
        Agent agent = null;
        while (iterator.hasNext()){
            Passenger passenger = (Passenger) iterator.next();
            Person person = populationFactory.createPerson(Id.createPersonId(passenger.getPassengerId()));
            Plan plan = populationFactory.createPlan();
            person.addPlan(plan);
            if (passenger.tripList.size() > 0){
                population.addPerson(person);
                agent = new Agent(person.getId().toString());
                mapOfAllAgents.put(person.getId().toString(), agent);
                mapOfAllAgents.get(person.getId().toString()).addPlan(plan);
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
                    agent.addActivity(activity);
                    if (!isLastActivity){
                        addLegToPlan(populationFactory, plan);
                    } else if ((passenger.tripList.size() > 1) && tripIndex > 1){
                        Activity firstActivity = (Activity) person.getPlans().get(0).getPlanElements().get(0);
                        //Добавляем сведения о доме агентов (линк где находится и координаты)
                        mapOfAllAgents.get(person.getId().toString()).addHome(firstActivity.getLinkId(), scenario.getNetwork().getLinks().get(firstActivity.getLinkId()).getCoord());
                        Activity lastActivity = getActivity(scenario, populationFactory, scenario.getNetwork().getLinks().get(firstActivity.getLinkId()).getCoord(), firstActivity.getType(), false);

                        lastActivity.setEndTime(25 * 3600);
                        addLegToPlan(populationFactory, plan);

                        plan.addActivity(lastActivity);
                        agent.addActivity(lastActivity);

                    } else {
                            Stop endStop;
                            if (!(stopMap.get(trip.endStopId) == null)){
                                endStop = (Stop) stopMap.get(trip.endStopId);
                            } else {
                                Double randomStopIndexDouble = (stopList.size() * Math.random() - 0.5);
                                int randomStopIndex = randomStopIndexDouble.intValue();
                                endStop = (Stop) stopList.get(randomStopIndex);
                            }
                            Activity homeActivity = addHomeActivityAtEndStop(ct, populationFactory, plan, endStop, scenario);
                            agent.addActivity(homeActivity);
                    }
                }
            }


                if (((plan.getPlanElements().size() - 1) / 2) < tripIndex){
                    population.removePerson(person.getId());
                    mapOfAllAgents.remove(person.getId().toString());
                    removedPersonsWithAnomalyInLegNumber++;
                }
        }

        System.out.println("Removed " + removedPersonsWithAnomalyInLegNumber + " persons with anomaly in leg number");
        System.out.println("Removed persons with empty plans: " + personsWithEmptyPlans);
        System.out.println("Removed persons with legs on end: " + personsWithLegsOnEnd);
        deleteOrFixFaultyPlans(population);
        for(Agent agent_ : mapOfAllAgents.values()) {
            addHomeRegistration(agent_);
        }

        File outputDir = new File("output");
        if(!outputDir.exists()) {
            outputDir.mkdir();
        }
        PopulationWriter populationWriter = new PopulationWriter(population);
        populationWriter.writeV5("output/PopulationOnLinks.xml.gz");


        if (createAdditionalAgents){
            if(!recordWithValidationPopulation) {
                population = ScenarioUtils.loadScenario(config).getPopulation();
            }

            HashMap<Id<Link>, HashSet<Agent>> mapOfCreatedAgentsOnLinks = increasePopulationFromStatistics(scenario, ct);
            for(HashSet<Agent> createdAgents : mapOfCreatedAgentsOnLinks.values()) {
                for(Agent created_agent : createdAgents) {
                    Person create_person = populationFactory.createPerson(Id.create(created_agent.getAgentId(), Person.class));
                    create_person.addPlan(created_agent.getPlan());
                    population.addPerson(create_person);
                }
            }
            mapOfAllAgents.putAll(mapOfCreatedAgents);
        }

        deleteOrFixFaultyPlans(population);


        System.out.println("Total number of agents: "+population.getPersons().size());
        populationWriter.writeV5("output/plans_with_created_agents(nearest_"+numberOfAgentsForSelectionPlan+").xml.gz");
    }

    private static HashMap<Id<Link>, HashSet<Agent>> increasePopulationFromStatistics(Scenario scenario, CoordinateTransformation ct) {
        HashMap<Id<Link>, HashSet<Agent>> mapOfCreatedAgentsOnLinks = readPopulationStatistics(filePopulationStatistics, scenario, ct);
        PopulationFactory populationFactory = scenario.getPopulation().getFactory();
        Random r = new Random();
        int inc_set = 0;
        for(HashSet<Agent> setOfCreatedAgents:mapOfCreatedAgentsOnLinks.values()) {
            inc_set++;
            System.out.println("-------------------------------------------------------");
            System.out.println(inc_set+" sets of created agents from " + mapOfCreatedAgentsOnLinks.size() +" were processed");
            System.out.println("HOME COORD OF SET: "+setOfCreatedAgents.stream().findFirst().get().getHomeLinkCoord());
            System.out.println("-------------------------------------------------------");

            List<Agent> agentsFoundNearby = searchNearbyAgents(scenario, setOfCreatedAgents.stream().findFirst().get().getHomeLinkCoord()).stream().collect(Collectors.toList());

            for(Agent agent:setOfCreatedAgents) {
                //из вернувшегося сета ближайших агентов выбираем произвольного
                Agent randomAgentForCloning = agentsFoundNearby.get(r.nextInt(agentsFoundNearby.size()-1));
                //получаем список его активностей
                List<Activity> randomListOfActivities = randomAgentForCloning.getListOfActivities();
                //получаем список его легов
                List<Leg> randomListOfLegs = TripStructureUtils.getLegs(randomAgentForCloning.getPlan());
                //для каждого объекта создаем план
                Plan plan = populationFactory.createPlan();
                //создаем домашнюю активность на основе первой активности рэндомного агента
                Activity firstActivity = PopulationUtils.createActivity(randomListOfActivities.get(0));
                //меняем у созданной первой активности homeLinkId
                firstActivity.setLinkId(agent.getHomeLinkId());
                //Добавляем данную активность в общий список активностей
                agent.addActivity(firstActivity);
                //добавляем в план

                plan.addActivity(firstActivity);
                plan.addLeg(randomListOfLegs.get(0));
                //в цикле без изменений копируем промежуточные активности (между первой и последней активностью)
                for (int i = 1; i < randomListOfActivities.size()-1; i++) {
                    Activity intermediateActivity = PopulationUtils.createActivity(randomListOfActivities.get(i));
                    agent.addActivity(intermediateActivity);
                    plan.addActivity(intermediateActivity);
                }

                //добавляем лег для промежуточных активностей
                plan.addLeg(randomListOfLegs.get(0));
                //создаем последнюю домашнюю активность на основе последней активности рэндомного агента
                Activity lastActivity = PopulationUtils.createActivity(randomListOfActivities.get(randomListOfActivities.size()-1));
                //меняем у созданной последней активности homeLinkId
                lastActivity.setLinkId(agent.getHomeLinkId());
                //добавляем последнюю активность в общий список активностей агента
                agent.addActivity(lastActivity);
                //добавляем в план
                plan.addActivity(lastActivity);

                //записываем объекту созданный план
                if(plan!=null) {
                    agent.addPlan(plan);
                } else {
                    System.out.println("Something went wrong! The requested agent has no plan!");
                }

            }
        }
        return mapOfCreatedAgentsOnLinks;
    }

    private static HashSet<Agent> searchNearbyAgents(Scenario scenario, Coord coord) {
        HashSet<Agent> SetOfFoundAgents = new HashSet<>();
        int distance = initialSearchDistanceOfNearestNodes;
        while(true) {
            List<Node> coll = NetworkUtils.getNearestNodes(scenario.getNetwork(), coord, distance).stream()
                    .sorted(Comparator.comparingDouble(a -> NetworkUtils.getEuclideanDistance(a.getCoord(), coord))).collect(Collectors.toList());
            for(Node node : coll) {
                for(Id<Link> linkId : NetworkUtils.getIncidentLinks(node).keySet()) {
                    if(mapOfAgentsOnLinks.containsKey(linkId)) {
                        for(Agent agent : mapOfAgentsOnLinks.get(linkId)) {
                            SetOfFoundAgents.add(agent);
                            if(SetOfFoundAgents.size() >= numberOfAgentsForSelectionPlan) {
                                //System.out.println("Found 10 agents at a distance "+(distance/1000)+" m.");
                                return SetOfFoundAgents;
                            }
                        }
                    }
                }
            }
            distance += searchExpansionStep;
        }
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

    private static void addHomeRegistration(Agent ag) {
            if(!mapOfAgentsOnLinks.containsKey(ag.getHomeLinkId())) {
                mapOfAgentsOnLinks.put(ag.getHomeLinkId(), new HashSet<>());
            }
            mapOfAgentsOnLinks.get(ag.getHomeLinkId()).add(ag);
    }

    private static Activity addHomeActivityAtEndStop(CoordinateTransformation ct, PopulationFactory populationFactory, Plan plan, Stop endStop, Scenario scenario) {
        Coord endStopCoord = endStop.getCoord();
        boolean isMetro = endStop.modes.contains("metro");
        Coord transformedEndStopCoord = ct.transform(endStopCoord);
        Activity lastActivity = getActivity(scenario, populationFactory, transformedEndStopCoord, "h", isMetro);
        lastActivity.setEndTime(25*3600);
        addLegToPlan(populationFactory, plan);
        plan.addActivity(lastActivity);
        return lastActivity;
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
            System.out.println("-------------------------------------------------------");
            System.out.println("STARTING TO READ TRIPS");
            System.out.println("-------------------------------------------------------");
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
        }
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

    private static HashMap<Id<Link>, HashSet<Agent>> readPopulationStatistics(String filePopulationStatistics, Scenario scenario, CoordinateTransformation ct) {
        HashMap<Id<Link>, HashSet<Agent>> mapOfCreatedAgentsOnLinks = new HashMap<>();
        int numOfAgents = 0;
        try {
            System.out.println("-------------------------------------------------------");
            System.out.println("STARTING TO READ POPULATION FROM STATISTICS");
            System.out.println("-------------------------------------------------------");

            BufferedReader bufferedReader = new BufferedReader(new FileReader(filePopulationStatistics));
            String line;
            while((line = bufferedReader.readLine()) != null) {
                if (line.contains("num_of_peoples")) continue;
                String[] items = line.split(",");
                Coord coord = ct.transform(new Coord(Double.valueOf(items[1]), Double.valueOf(items[0])));
                Link nearestLink = NetworkUtils.getNearestLinkExactly(scenario.getNetwork(), coord);
                Id<Link> homeLinkId = nearestLink.getId();
                Coord homeLinkCoord = nearestLink.getCoord();
                for (int i = 0; i < Integer.valueOf(items[2]); i++) {
                    numOfAgents++;
                    Agent agent = new Agent("created_agentID"+numOfAgents);
                    agent.addHome(homeLinkId, homeLinkCoord);
                    mapOfCreatedAgents.put(agent.getAgentId(), agent);
                    if(!mapOfCreatedAgentsOnLinks.containsKey(homeLinkId)) {
                        mapOfCreatedAgentsOnLinks.put(homeLinkId, new HashSet<>());
                    }
                    mapOfCreatedAgentsOnLinks.get(homeLinkId).add(agent);
                    String standardOfLiving;
                    double rand = Math.random();
                    if(rand <= Double.valueOf(items[3])) {
                        standardOfLiving = "low";
                    } else if (rand <= Double.valueOf(items[4])) {
                        standardOfLiving = "medium";
                    } else {
                        standardOfLiving = "high";
                    }
                    agent.setStandardOfLiving(standardOfLiving);
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("After read: "+numOfAgents);
        return mapOfCreatedAgentsOnLinks;
    }

    private static void readStations(String fileStations, Map stopMap, List stopList) {
        try {
            System.out.println("-------------------------------------------------------");
            System.out.println("STARTING TO READ STATIONS");
            System.out.println("-------------------------------------------------------");
            BufferedReader bufferedReader = new BufferedReader(new FileReader(fileStations));
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
