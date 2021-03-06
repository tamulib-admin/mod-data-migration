package org.folio.rest.migration.model;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.folio.rest.jaxrs.model.organizations.acq_models.mod_orgs.schemas.Account;
import org.folio.rest.jaxrs.model.organizations.acq_models.mod_orgs.schemas.Account.PaymentMethod;
import org.folio.rest.migration.model.request.vendor.VendorDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VendorAccountRecord {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final String vendorId;
  private final String deposit;
  private final String name;
  private final String notes;
  private final String number;
  private final String status;

  public VendorAccountRecord(String vendorId, String deposit, String name, String notes, String number, String status) {
    this.vendorId = vendorId;
    this.deposit = deposit;
    this.name = name;
    this.notes = notes;
    this.number = number;
    this.status = status;
  }

  public String getDeposit() {
    return deposit;
  }

  public String getName() {
    return name;
  }

  public String getNotes() {
    return notes;
  }

  public String getNumber() {
    return number;
  }

  public String getStatus() {
    return status;
  }

  public Account toAccount(VendorDefaults defaults) {
    final Account account = new Account();

    account.setLibraryCode(StringUtils.SPACE);
    account.setLibraryEdiCode(StringUtils.SPACE);

    setName(account);
    setNotes(account);
    setNumber(account);
    setPaymentMethod(account, defaults);
    setStatus(account);

    return account;
  }

  private void setName(Account account) {
    if (Objects.isNull(name)) {
      account.setName(StringUtils.SPACE);
    } else {
      account.setName(name);
    }
  }

  private void setNotes(Account account) {
    if (Objects.nonNull(number)) {
      account.setNotes(notes);
    }
  }

  private void setNumber(Account account) {
    if (Objects.isNull(number)) {
      account.setAccountNo(StringUtils.SPACE);
    } else {
      account.setAccountNo(number);
    }
  }

  private void setPaymentMethod(Account account, VendorDefaults defaults) {
    if (Objects.nonNull(deposit) && deposit.equalsIgnoreCase("y")) {
      account.setPaymentMethod(PaymentMethod.DEPOSIT_ACCOUNT);
    } else if (Objects.nonNull(defaults.getPaymentMethod())) {
      String defaultPayment = defaults.getPaymentMethod();
      
      if (defaultPayment.equalsIgnoreCase(PaymentMethod.CASH.value())) {
        account.setPaymentMethod(PaymentMethod.CASH);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.CREDIT_CARD.value())) {
        account.setPaymentMethod(PaymentMethod.CREDIT_CARD);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.EFT.value())) {
        account.setPaymentMethod(PaymentMethod.EFT);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.DEPOSIT_ACCOUNT.value())) {
        account.setPaymentMethod(PaymentMethod.DEPOSIT_ACCOUNT);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.PHYSICAL_CHECK.value())) {
        account.setPaymentMethod(PaymentMethod.PHYSICAL_CHECK);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.BANK_DRAFT.value())) {
        account.setPaymentMethod(PaymentMethod.BANK_DRAFT);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.INTERNAL_TRANSFER.value())) {
        account.setPaymentMethod(PaymentMethod.INTERNAL_TRANSFER);
      } else if (defaultPayment.equalsIgnoreCase(PaymentMethod.OTHER.value())) {
        account.setPaymentMethod(PaymentMethod.OTHER);
      } else {
        log.error("unknown default paymentMethod {} for vendor id {}", defaultPayment, vendorId);
      }
    }
  }

  private void setStatus(Account account) {
    if (Objects.nonNull(status) && status.contentEquals("0")) {
      account.setAccountStatus("active");
    } else {
      account.setAccountStatus("inactive");
    }
  }

}
