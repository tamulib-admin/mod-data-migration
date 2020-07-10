package org.folio.rest.migration.model;

import org.folio.rest.migration.model.request.vendor.VendorDefaults;
import org.folio.rest.migration.model.request.vendor.VendorMaps;

public abstract class AbstractVendorRecord {

  protected VendorMaps maps;
  protected VendorDefaults defaults;

  public VendorMaps getMaps() {
    return maps;
  }

  public void setMaps(VendorMaps vendorMaps) {
    this.maps = vendorMaps;
  }

  public VendorDefaults getDefaults() {
    return defaults;
  }

  public void setDefaults(VendorDefaults vendorDefaults) {
    this.defaults = vendorDefaults;
  }

}