package org.matsim.plans;

import org.matsim.api.core.v01.Coord;

public class Stop {
    String stop_id;
    String name;
    String modes;
    Coord coord;

    public Stop(String stop_id, String name, String modes, Coord coord) {
        this.stop_id = stop_id;
        this.name = name;
        this.modes = modes;
        this.coord = coord;
    }

    public String getStop_id() {
        return stop_id;
    }

    public void setStop_id(String stop_id) {
        this.stop_id = stop_id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModes() {
        return modes;
    }

    public void setModes(String modes) {
        this.modes = modes;
    }

    public Coord getCoord() {
        return coord;
    }

    public void setCoord(Coord coord) {
        this.coord = coord;
    }
}
