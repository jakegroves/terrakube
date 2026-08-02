import { Select } from "antd";

// jobMetric filters/groups by calendar day (the `day` dimension truncates
// timestamps to dates), not by hour — so these stay day-granular rather than
// offering an "hour" option that would be misleading.
export type RangeDays = 1 | 3 | 7;

type Props = {
  value: RangeDays;
  onChange: (days: RangeDays) => void;
  options?: RangeDays[];
};

const LABELS: Record<RangeDays, string> = {
  1: "Today",
  3: "Last 3 days",
  7: "Last 7 days",
};

export const MetricsTimeRangeSelect = ({ value, onChange, options = [1, 3, 7] }: Props) => (
  <Select<RangeDays>
    value={value}
    onChange={(days) => onChange(days)}
    options={options.map((days) => ({ value: days, label: LABELS[days] }))}
    style={{ width: 160 }}
  />
);
