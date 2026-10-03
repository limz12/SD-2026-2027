package tcp01;

import java.io.Serializable;

/**
 * Pessoa transportada na mensagem do cliente.
 *
 * Tem de ser {@link Serializable} para atravessar a stream de objetos.
 * O {@code serialVersionUID} é declarado explicitamente: sem ele, o Java
 * calcula-o a partir da estrutura da classe e qualquer alteração — mesmo
 * acrescentar um método — poderia tornar as versões incompatíveis sem o
 * programador dar por isso. Aqui controla-se a versão à mão.
 *
 * A referência para {@link Place} é serializada automaticamente: basta
 * escrever a Person para o Place partir com ela.
 *
 * Esta classe existe com o mesmo nome completo (tcp01.Person) nos dois
 * projetos: quem serializa e quem desserializa precisam dela.
 */
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

    public Place getPlace() {
        return place;
    }

    public int getYear() {
        return year;
    }

    public int getIdade(int anoAtual) {
        return anoAtual - year;
    }
}

