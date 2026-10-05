package tcp01;

import java.io.Serializable;

/**
 * Place é referenciado por Person e viaja com ela sem nenhuma escrita
 * explícita: basta que também implemente Serializable. Se não implementasse,
 * o writeObject(person) do cliente falhava com NotSerializableException —
 * é aí, no lado de quem ESCREVE, que o erro aparece, porque é o
 * ObjectOutputStream que percorre o grafo de objetos e descobre a classe
 * não serializável.
 *
 * Tem de existir, com o mesmo pacote e o mesmo nome completo, nos dois
 * projetos: o cliente precisa dela para construir a Person; o servidor
 * precisa dela para que o ObjectInputStream consiga reconstruir o objeto
 * que chegou dentro da Person.
 */
public class Place implements Serializable {

  // Declarado explicitamente: se não fosse, o Java calculava-o a partir da
  // estrutura da classe, e qualquer alteração (mesmo um método novo) podia
  // mudar o valor e tornar as versões dos dois lados incompatíveis sem
  // nenhuma intenção da nossa parte.
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

  @Override
  public String toString() {
    return postalCode + " " + locality;
  }
}