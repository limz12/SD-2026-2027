package tcp01;

import java.io.Serializable;

/**
 * Localidade associada a uma {@link Person}.
 *
 * Faz parte do grafo de objetos enviado com a Person: sem nenhuma chamada
 * de escrita própria, o Java serializa-a automaticamente desde que seja
 * {@link Serializable}. Também aqui o {@code serialVersionUID} é explícito.
 *
 * Existe, igual e com o mesmo nome completo (tcp01.Place), nos dois projetos.
 */
public class Place implements Serializable {

    private static final long serialVersionUID = 1L;

    private String postalCode;
    private String locality;

    public Place(String postalCode, String locality) {
        this.postalCode = postalCode;
        this.locality = locality;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getLocality() {
        return locality;
    }
}
