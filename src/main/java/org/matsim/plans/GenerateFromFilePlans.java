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
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class GenerateFromFilePlans {
    private static String networkInputFile = "scenarios/zsd/network_spb_zsd_newcapasity_after_5.xml";
    private static String filePopulationStatistics = "input/inputForPlans/tripsFromValidations/cik_final.csv";
    private static String masterPlanFile = "input/masterPlans.xml.gz";
    private static HashMap<String, Agent> mapOfAllAgents = new HashMap<>();
    private static HashMap<Id<Link>, HashSet<Agent>> mapOfAgentsOnLinks = new HashMap<>();
    private static HashMap<String, Agent> mapOfCreatedAgents = new HashMap<>();
    //Изначальная дистанция поиска
    private static int initialSearchDistanceOfNearestNodes = 100;
    //Если необходимое количество агентов не найдено, то шаг увеличения дистанции
    private static int searchExpansionStep = 50;
    //Сколько ближайших агентов необходимо найти
    private static int numberOfAgentsForSelectionPlan = 100;


    public static void main(String[] args) {
        File outputDir = new File("output");
        if(!outputDir.exists()) {
            outputDir.mkdir();
        }
        String inputCRS = "EPSG:4326"; // WGS84
        String outputCRS = "EPSG:32635";
        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(inputCRS, outputCRS);
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkInputFile);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Population population = scenario.getPopulation();
        PopulationFactory populationFactory = population.getFactory();

        PopulationReader populationReader = new PopulationReader(scenario);
        populationReader.readFile(masterPlanFile);

        for(Person person : scenario.getPopulation().getPersons().values()) {
            String name = person.getId().toString();
            Agent agent = new Agent(name);
            mapOfAllAgents.put(name, agent);
            agent.addPlan(person.getSelectedPlan());
            Id<Link> homeLinkId= PopulationUtils.getFirstActivity(person.getSelectedPlan()).getLinkId();
            agent.addHome(homeLinkId, scenario.getNetwork().getLinks().get(homeLinkId).getCoord());
            for(Activity activity:PopulationUtils.getActivities(person.getSelectedPlan(), null)) {
                agent.addActivity(activity);
            }
            addHomeRegistration(agent);
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

        deleteOrFixFaultyPlans(population);

        PopulationWriter populationWriter = new PopulationWriter(population);
        populationWriter.writeV5("output/plans_with_created_agents(nearest_"+numberOfAgentsForSelectionPlan+").xml.gz");
    }

    private static void addHomeRegistration(Agent ag) {
        if(!mapOfAgentsOnLinks.containsKey(ag.getHomeLinkId())) {
            mapOfAgentsOnLinks.put(ag.getHomeLinkId(), new HashSet<>());
        }
        mapOfAgentsOnLinks.get(ag.getHomeLinkId()).add(ag);
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


}
