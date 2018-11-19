package org.matsim.plans;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;

import java.util.ArrayList;
import java.util.List;


public class Agent {
    private String AgentId;
    private Id<Link> homeLinkId;
    private Coord homeLinkCoord;
    private Plan plan;
    private String standardOfLiving;
    private List<Activity> listOfActivities = new ArrayList<>();

    public Agent (String AgentId) {
        this.AgentId = AgentId;
    }

    public void addActivity(Activity activity) {
        listOfActivities.add(activity);

    }

    public void addHome(Id<Link> linkId, Coord coord) {
        homeLinkId = linkId;
        homeLinkCoord = coord;
    }

    public void addPlan(Plan plan) {
        this.plan = plan;
    }

    public void setStandardOfLiving(String standardOfLiving) {
        this.standardOfLiving = standardOfLiving;
    }

    public String getAgentId() {
        return AgentId;
    }

    public List<Activity> getListOfActivities() {
        return listOfActivities;
    }

    public Id<Link> getHomeLinkId() {
        return homeLinkId;
    }

    public Plan getPlan() {
        return plan;
    }

    public Coord getHomeLinkCoord() {
        return homeLinkCoord;
    }
}


