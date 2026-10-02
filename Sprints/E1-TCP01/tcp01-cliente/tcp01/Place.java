package tcp01;

import java.io.Serializable;

public class Place implements Serializable {
    private static final long serialVersionUID = 1L;
    private String postalCode;
    private String locality;


    public Place(String _postalCode, String _locality){
        this.postalCode = _postalCode;
        this.locality = _locality;
    }

}
