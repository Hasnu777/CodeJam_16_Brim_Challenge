import { Text } from "@mantine/core";
import { money, num } from "../lib/format";

/** Responsive monthly line plot. Chronological ordering is independent of API object order. */
export function MonthlySpendChart({
  monthly,
}: {
  monthly: Record<string, string>;
}) {
  const data = Object.entries(monthly).sort(([a], [b]) => a.localeCompare(b));
  if (!data.length)
    return (
      <Text size="sm" c="dimmed" py="xl" ta="center">
        No spending in this date range.
      </Text>
    );
  const values = data.map(([, value]) => num(value));
  const min = Math.min(0, ...values);
  const max = Math.max(1, ...values);
  const span = max - min;
  const x = (index: number) =>
    data.length === 1 ? 355 : 70 + (index * 570) / (data.length - 1);
  const y = (value: number) => 210 - ((value - min) / span) * 185;
  const points = data
    .map(([, value], index) => `${x(index)},${y(num(value))}`)
    .join(" ");
  return (
    <svg
      viewBox="0 0 660 250"
      width="100%"
      role="img"
      aria-label="Monthly spending in Canadian dollars"
      style={{ display: "block" }}
    >
      <title>Monthly spending in Canadian dollars</title>
      {[0, 1, 2, 3, 4].map((tick) => {
        const value = min + (span * tick) / 4;
        return (
          <g key={tick}>
            <line
              x1={70}
              x2={640}
              y1={y(value)}
              y2={y(value)}
              stroke="var(--mantine-color-default-border)"
            />
            <text
              x={62}
              y={y(value) + 4}
              textAnchor="end"
              fontSize={11}
              fill="var(--mantine-color-dimmed)"
            >
              {money(value)}
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
            aria-label={`${month}: ${money(value)}`}
          >
            <title>
              {month}: {money(value)}
            </title>
          </circle>
          <text
            x={x(index)}
            y={235}
            fontSize={11}
            textAnchor="middle"
            fill="var(--mantine-color-dimmed)"
          >
            {month.slice(5)}
          </text>
        </g>
      ))}
    </svg>
  );
}
