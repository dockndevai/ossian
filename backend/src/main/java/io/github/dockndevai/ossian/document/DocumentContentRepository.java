package io.github.dockndevai.ossian.document;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentContentRepository extends JpaRepository<DocumentContent, UUID> {

}
