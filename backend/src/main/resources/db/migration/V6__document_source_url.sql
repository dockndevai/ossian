-- Where a source came from, when it came from a URL rather than an upload.
--
-- Kept because a page and a file are not equivalent afterwards: a page can be re-fetched and can
-- change underneath the copy that was indexed, and neither is true of an uploaded file. Without
-- the address there is no way to tell which kind of source you are looking at, or to go back to
-- the original.
ALTER TABLE documents ADD COLUMN source_url VARCHAR(2000);
