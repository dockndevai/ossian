import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type DocumentView } from "../api/client";

export default function DocumentsPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    try {
      setDocs((await api.documents(token)).content);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token]);

  useEffect(() => {
    void load();
    // Ingestion is asynchronous, so poll while anything is still in flight rather than
    // leaving the user staring at a stale PROCESSING row.
    const timer = setInterval(() => void load(), 4000);
    return () => clearInterval(timer);
  }, [load]);

  async function upload(file: File) {
    setUploading(true);
    setError(null);
    try {
      const res = await api.upload(token, file);
      if (res.duplicate) setError("That file is already in the corpus — nothing was re-embedded.");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  return (
    <section className="panel">
      <h2>Documents</h2>
      <p className="muted">PDF, DOCX, HTML, Markdown or plain text. Up to 25 MB.</p>

      <input
        ref={fileInput}
        type="file"
        disabled={uploading}
        onChange={(e) => e.target.files?.[0] && void upload(e.target.files[0])}
      />
      {uploading && <span className="muted"> uploading…</span>}
      {error && <p className="error">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>File</th><th>Status</th><th>Chunks</th><th>Size</th><th>Uploaded by</th><th></th>
          </tr>
        </thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.id}>
              <td>
                <strong>{d.title ?? d.filename}</strong>
                {d.title && d.title !== d.filename && <div className="muted small">{d.filename}</div>}
                {d.errorMessage && <div className="error small">{d.errorMessage}</div>}
              </td>
              <td><span className={`chip ${d.status === "READY" ? "ok" : d.status === "FAILED" ? "bad" : "warn"}`}>{d.status}</span></td>
              <td>{d.chunkCount}</td>
              <td>{(d.sizeBytes / 1024).toFixed(0)} KB</td>
              <td className="muted">{d.uploadedBy}</td>
              <td>
                <button
                  onClick={async () => {
                    await api.deleteDocument(token, d.id);
                    await load();
                  }}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
          {docs.length === 0 && (
            <tr><td colSpan={6} className="muted">Nothing ingested yet.</td></tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
