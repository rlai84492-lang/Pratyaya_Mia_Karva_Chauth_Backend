package org.example.karvachauth.repository;

import org.example.karvachauth.entity.MessageStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageStatusEventRepository extends JpaRepository<MessageStatusEvent, Long> {
}