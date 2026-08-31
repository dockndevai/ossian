-- Chunking per namespace.
--
-- One chunk size for a whole installation is a compromise between documents that have nothing in
-- common. A runbook answers best in small passages, because a question is about one procedure; a
-- contract answers worst that way, because a clause cut in half means the opposite of what it
-- says. Namespaces already separate those corpora, so this is where the setting belongs.
--
-- Null means "use the installation default", so an existing namespace keeps behaving exactly as
-- it did and nothing has to be re-indexed on upgrade.
ALTER TABLE namespaces ADD COLUMN chunk_size    INT;
ALTER TABLE namespaces ADD COLUMN chunk_overlap INT;
