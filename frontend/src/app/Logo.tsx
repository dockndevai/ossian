import { useId } from "react";

/**
 * The Ossian mark.
 *
 * An O whose ring is broken into eight strata — the corpus, in layers — with a gap cut straight
 * down through them where the question passes, and one solid band held inside: the passage that
 * actually answered. The cut breaks the ring but not the band, which is the point.
 *
 * Two things about the band are deliberate. It is inset rather than spanning the full width —
 * touching the ring on both sides turns the glyph into a theta. And it sits below centre rather
 * than on the diameter, because a centred bar inside a circle is the universal "prohibited"
 * sign; dropped, it reads as one layer among the strata, which is what it is.
 *
 * The ring is a dashed circle rather than hand-placed arcs, so the strata stay exactly even and
 * the O never looks broken on one side. The cut is a mask, not a shape painted in the background
 * colour, so the logo is genuinely transparent there and survives a dark header, a light one, or
 * a coloured tile.
 */
export default function Logo({ size = 26, title = "Ossian" }: { size?: number; title?: string }) {
  // Ids must be unique per instance: two logos on a page sharing a mask id would have the
  // second silently reuse the first's.
  const maskId = useId();

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      role="img"
      aria-label={title}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <title>{title}</title>
      <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="32" height="32">
        <rect width="32" height="32" fill="#fff" />
        {/* The question, cutting down through the strata… */}
        <rect x="13.7" y="-1" width="4.6" height="34" fill="#000" />
        {/* …except across the retrieved band, which the answer spans. */}
        <rect x="13.7" y="17.2" width="4.6" height="3.5" fill="#fff" />
      </mask>

      <g mask={`url(#${maskId})`}>
        <circle
          cx="16"
          cy="16"
          r="12.4"
          stroke="currentColor"
          strokeWidth="2.7"
          strokeLinecap="round"
          strokeDasharray="7.21 2.53"
          strokeDashoffset="3.6"
          opacity="0.62"
        />
        <rect x="8.4" y="17.2" width="15.2" height="3.5" rx="1.75" fill="currentColor" />
      </g>
    </svg>
  );
}
