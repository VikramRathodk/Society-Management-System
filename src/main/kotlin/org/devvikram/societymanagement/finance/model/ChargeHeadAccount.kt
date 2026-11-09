package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

// Which ledger a maintenance charge head credits when bills are issued. No row: Maintenance charges.
@Entity
@Table(name = "finance_charge_head_account")
class ChargeHeadAccount(
    @Id
    @Column(name = "charge_head_id")
    var chargeHeadId: Long,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,
)
