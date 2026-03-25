document.addEventListener("DOMContentLoaded", () => {
  console.log("hourly: init");
  const dataEl = document.getElementById("hourly-data");
  if (!dataEl) {
    console.log("hourly: missing data element");
    return;
  }

  let data;
  try {
    data = JSON.parse(dataEl.textContent || "{}");
  } catch (err) {
    console.log("hourly: failed to parse data", err);
    return;
  }

  const times = Array.isArray(data.times) ? data.times : [];
  const temps = Array.isArray(data.temps) ? data.temps : [];
  const precip = Array.isArray(data.precip) ? data.precip : [];
  if (times.length === 0) {
    console.log("hourly: no times");
    return;
  }
  console.log("hourly: points", times.length);

  const yMin = Number.isFinite(data.yMin) ? data.yMin : 0;
  const yMax = Number.isFinite(data.yMax) ? data.yMax : 100;
  const tickStep = Math.max(1, Math.ceil(times.length / 8));
  const tickVals = times.filter((_, i) => i % tickStep === 0);

  const tempTrace = {
    x: times,
    y: temps,
    mode: "lines+markers",
    name: "Temp (\u00B0F)",
    line: { color: "#f07a8c", width: 3 },
    marker: { color: "#f07a8c", size: 6 },
  };

  const precipTrace = {
    x: times,
    y: precip,
    mode: "lines+markers",
    name: "Precip (%)",
    yaxis: "y2",
    line: { color: "#41d4ff", width: 2, dash: "dot" },
    marker: { color: "#41d4ff", size: 5 },
  };

  const layout = {
    paper_bgcolor: "rgba(0,0,0,0)",
    plot_bgcolor: "rgba(0,0,0,0)",
    margin: { t: 20, r: 40, b: 40, l: 40 },
    xaxis: {
      title: "Time",
      tickfont: { color: "#b7c0d6" },
      gridcolor: "#243247",
      tickangle: 0,
      tickmode: "array",
      tickvals: tickVals,
      ticktext: tickVals,
    },
    yaxis: {
      title: "Temp (\u00B0F)",
      range: [yMin, yMax],
      tickfont: { color: "#b7c0d6" },
      gridcolor: "#243247",
    },
    yaxis2: {
      title: "Precip (%)",
      range: [0, 100],
      overlaying: "y",
      side: "right",
      tickfont: { color: "#b7c0d6" },
      gridcolor: "rgba(0,0,0,0)",
    },
    legend: { font: { color: "#b7c0d6" } },
  };

  Plotly.newPlot("hourly-chart", [tempTrace, precipTrace], layout, { displayModeBar: false });
  console.log("hourly: chart rendered");
});
