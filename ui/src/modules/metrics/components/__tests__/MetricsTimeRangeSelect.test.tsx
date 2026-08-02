import { fireEvent, render, screen } from "@testing-library/react";
import { MetricsTimeRangeSelect } from "../MetricsTimeRangeSelect";

describe("MetricsTimeRangeSelect", () => {
  it("shows the current value's label", () => {
    render(<MetricsTimeRangeSelect value={3} onChange={jest.fn()} />);
    expect(screen.getByText("Last 3 days")).toBeInTheDocument();
  });

  it("calls onChange with the selected day count", () => {
    const onChange = jest.fn();
    render(<MetricsTimeRangeSelect value={3} onChange={onChange} />);

    fireEvent.mouseDown(screen.getByRole("combobox"));
    fireEvent.click(screen.getByText("Today"));

    expect(onChange).toHaveBeenCalledWith(1);
  });

  it("restricts options when a custom options list is given", () => {
    render(<MetricsTimeRangeSelect value={1} onChange={jest.fn()} options={[1, 3]} />);
    fireEvent.mouseDown(screen.getByRole("combobox"));
    expect(screen.queryByText("Last 7 days")).not.toBeInTheDocument();
  });
});
