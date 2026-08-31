package io.github.dockndevai.openbook.document;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentContentRepository extends JpaRepository<DocumentContent, UUID> {

}
