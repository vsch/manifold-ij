package json.rename;

import json.sample.StrangeUriFormats;

public class TestJsonRename_Union_Property {
  public static void main(String[] args) {
    StrangeUriFormats json = StrangeUriFormats.create();
    json.getNc_Vehiclezzz();
    json.getNc_VehiclezzzAsnc_VehicleType();
    json.getNc_VehiclezzzAsListOfnc_VehicleType();
    json.setNc_Vehiclezzz( null );
    json.setNc_VehiclezzzAsnc_VehicleType( null );
    json.setNc_VehiclezzzAsListOfnc_VehicleType( null );
  }
}