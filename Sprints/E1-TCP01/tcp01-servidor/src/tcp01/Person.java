package tcp01;

import java.io.Serializable;

/**
 * Person tem de existir nos DOIS projetos, com o mesmo pacote (tcp01) e o
 * mesmo nome completo. "Igual" não precisa de ser byte a byte: o que tem de
 * coincidir é o serialVersionUID. Acrescentar um método, por exemplo, nos
 * dois lados ao mesmo tempo não quebra nada (é a variante "continua a
 * funcionar" do ponto 11); mudar o serialVersionUID só de um lado, ou mudar
 * o pacote só de um lado, quebra — e são erros diferentes (ver Connection).
 */
public class Person implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private int year;
    private Place place; // referência para um objeto dependente, também Serializable

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
}