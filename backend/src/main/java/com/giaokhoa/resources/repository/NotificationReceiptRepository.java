package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.NotificationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository của {@code notification_receipt} do module resources sở hữu. */
public interface NotificationReceiptRepository
        extends JpaRepository<NotificationReceipt, NotificationReceipt.Key> {}
