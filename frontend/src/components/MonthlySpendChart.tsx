import { Text } from "@mantine/core";
import { money, moneyShort, MONTHS, num } from "../lib/format";

/** A round step (1, 2, 2.5 or 5 x 10^n) so the axis reads $0 / $60K / $120K... */
function niceStep(raw: number): number {
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * pow;
}

/**
 * Responsive monthly line plot. Chronological ordering is independent of API object order. Months are labelled
 * Jan, Feb...; the axis uses short amounts ($240K) and each point's exact amount is in its tooltip. maxHeight keeps the
 * chart from growing with the page width (fonts scale with the SVG).
 */
export function MonthlySpendChart({
  monthly,
  maxHeight = 300,
}: {
  monthly: Record<string, string>;
  maxHeight?: number;
}) {
  const data = Object.entries(monthly).sort(([a], [b]) => a.localeCompare(b));
  if (!data.length)
    return (
      <Text size="sm" c="dimmed" py="xl" ta="center">
        No spending in this date range.
      </Text>
    );
  const values = data.map(([, value]) => num(value));
  const step = niceStep(Math.max(1, ...values) / 4);
  const max = step * 4;
  const left = 56;
  const right = 640;
  const x = (index: number) =>
    data.length === 1 ? (left + right) / 2 : left + 14 + (index * (right - left - 28)) / (data.length - 1);
  const y = (value: number) => 210 - (value / max) * 185;
  const label = (month: string) => {
    const m = MONTHS[Number(month.slice(5, 7)) - 1] ?? month.slice(5);
    // Show the year only when the range spans more than one.
    return new Set(data.map(([k]) => k.slice(0, 4))).size > 1 ? `${m} ${month.slice(2, 4)}` : m;
  };
  const points = data
    .map(([, value], index) => `${x(index)},${y(num(value))}`)
    .join(" ");
  return (
    <svg
      viewBox="0 0 660 250"
      width="100%"
      role="img"
      aria-label="Monthly spending in Canadian dollars"
      style={{ display: "block", maxHeight, margin: "0 auto" }}
    >
      <title>Monthly spending in Canadian dollars</title>
      {[0, 1, 2, 3, 4].map((tick) => {
        const value = step * tick;
        return (
          <g key={tick}>
            <line
              x1={left}
              x2={right}
              y1={y(value)}
              y2={y(value)}
              stroke="var(--mantine-color-default-border)"
            />
            <text
              x={left - 8}
              y={y(value) + 4}
              textAnchor="end"
              fontSize={11}
              fill="var(--mantine-color-dimmed)"
            >
              {moneyShort(value)}
            </text>
          </g>
        );
      })}
      <polyline
        points={points}
        fill="none"
        stroke="var(--mantine-primary-color-filled)"
        strokeWidth={2.5}
        strokeLinejoin="round"
      />
      {data.map(([month, value], index) => (
        <g key={month}>
          <circle
            cx={x(index)}
            cy={y(num(value))}
            r={4}
            fill="var(--mantine-primary-color-filled)"
            tabIndex={0}
            role="img"
            aria-label={`${label(month)}: ${money(value)}`}
          >
            <title>
              {label(month)} {month.slice(0, 4)}: {money(value)}
            </title>
          </circle>
          <text
            x={x(index)}
            y={235}
            fontSize={11}
            textAnchor="middle"
            fill="var(--mantine-color-dimmed)"
          >
            {label(month)}
          </text>
        </g>
      ))}
    </svg>
  );
}
