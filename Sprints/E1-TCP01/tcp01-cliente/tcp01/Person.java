package tcp01;
import java.io.Serializable;

public class Person implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private int year;
    private Place place;


    //CONSTRUTOR
    public Person(String _name, Place _place,int _year){
        this.name = _name;
        this.year = _year;
        this.place = _place;
    }

}
