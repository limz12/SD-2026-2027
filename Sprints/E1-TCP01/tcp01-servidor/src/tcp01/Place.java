package tcp01;

import java.io.Serializable;

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

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    @Override
    public String toString() {
        return "Place{"
                + "postalCode='" + postalCode + '\''
                + ", locality='" + locality + '\''
                + '}';
    }
}