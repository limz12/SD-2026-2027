package tcp01;

import java.io.Serializable;

public class Person implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private int year;
    private Place place;

    public Person(String name, Place place, int year) {
        this.name = name;
        this.place = place;
        this.year = year;
    }

    public String getName() {
        return name;
    }

    public int getYear() {
        return year;
    }

    public Place getPlace() {
        return place;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public void setPlace(Place place) {
        this.place = place;
    }

    /*
     * Método acrescentado sem alterar o serialVersionUID.
     */
    public String getDescription() {
        return name + " vive em " + place.getLocality();
    }

    @Override
    public String toString() {
        return "Person{"
                + "name='" + name + '\''
                + ", year=" + year
                + ", place=" + place
                + '}';
    }
}