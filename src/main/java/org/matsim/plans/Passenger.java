package org.matsim.plans;

import java.util.ArrayList;
import java.util.List;

public class Passenger {
    String passengerId;
    List tripList;

    public Passenger(String passengerId) {
        this.passengerId = passengerId;
        this.tripList = new ArrayList();
    }

    public String getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(String passengerId) {
        this.passengerId = passengerId;
    }

}
