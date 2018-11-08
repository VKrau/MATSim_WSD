package org.matsim.plans;

public class Trip implements Comparable<Trip> {
    int tripId;
    String carsId;
    double startTime;
    String startStopId;
    double endTime;
    String endStopId;

    public Trip(int tripId, String carsId) {
        this.tripId = tripId;
        this.carsId = carsId;
    }

    public int getTripId() {
        return tripId;
    }

    public void setTripId(int tripId) {
        this.tripId = tripId;
    }

    public String getCarsId() {
        return carsId;
    }

    public void setCarsId(String carsId) {
        this.carsId = carsId;
    }

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public String getStartStopId() {
        return startStopId;
    }

    public void setStartStopId(String startStopId) {
        this.startStopId = startStopId;
    }

    public double getEndTime() {
        return endTime;
    }

    public void setEndTime(double endTime) {
        this.endTime = endTime;
    }

    public String getEndStopId() {
        return endStopId;
    }

    public void setEndStopId(String endStopId) {
        this.endStopId = endStopId;
    }

    @Override
    public int compareTo(Trip o) {
        return Double.compare(this.startTime, o.startTime);
    }
}
