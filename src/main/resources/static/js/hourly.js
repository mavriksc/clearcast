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

  const chart = document.getElementById("hourly-chart");
  if (!chart) {
    console.log("hourly: missing chart element");
    return;
  }

  const getTickStep = () => {
    const plotWidth = Math.max(1, chart.clientWidth - 80);
    const targetLabelWidth = chart.clientWidth <= 600 ? 72 : 90;
    const maxLabels = Math.max(3, Math.floor(plotWidth / targetLabelWidth));
    return Math.max(1, Math.ceil(times.length / maxLabels));
  };

  const getTickVals = () => {
    const step = getTickStep();
    return times.filter((_, i) => i % step === 0);
  };

  const yMin = Number.isFinite(data.yMin) ? data.yMin : 0;
  const yMax = Number.isFinite(data.yMax) ? data.yMax : 100;
  const tickStep = getTickStep();
  const tickVals = getTickVals();

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
    showlegend: chart.clientWidth > 600,
    legend: { font: { color: "#b7c0d6" } },
  };

  const iconRow = document.querySelector(".hourly-icons");
  const syncHourlyIcons = () => {
    if (!iconRow) return;

    const plotLeft = layout.margin.l;
    const plotRight = layout.margin.r;
    const plotWidth = Math.max(0, chart.clientWidth - plotLeft - plotRight);
    const iconStep = Number(iconRow.dataset.step) || tickStep;
    const intervalWidth = plotWidth * iconStep / Math.max(1, times.length);

    iconRow.querySelectorAll(".hourly-icon").forEach((icon) => {
      const hourIndex = Number(icon.dataset.hourIndex);
      const x = plotLeft + ((hourIndex + 0.5) / times.length) * plotWidth;
      icon.style.left = `${x}px`;
      icon.style.width = `${Math.max(1, intervalWidth - 4)}px`;
    });
  };

  const resizeChart = () => {
    window.requestAnimationFrame(() => {
      Plotly.Plots.resize(chart);
      const mobile = chart.clientWidth <= 600;
      const tickVals = getTickVals();
      Plotly.relayout(chart, {
        "xaxis.tickvals": tickVals,
        "xaxis.ticktext": tickVals,
        "xaxis.tickangle": mobile ? -35 : 0,
        "margin.b": mobile ? 56 : 40,
        showlegend: !mobile,
      });
      syncHourlyIcons();
    });
  };

  Plotly.newPlot(chart, [tempTrace, precipTrace], layout, {
    displayModeBar: false,
    responsive: true,
  }).then(resizeChart);

  window.addEventListener("resize", resizeChart);
  if ("ResizeObserver" in window) {
    const observer = new ResizeObserver(resizeChart);
    observer.observe(chart);
  }
  console.log("hourly: chart rendered");
});
