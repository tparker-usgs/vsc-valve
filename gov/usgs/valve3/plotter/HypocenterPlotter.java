package gov.usgs.valve3.plotter;

import gov.usgs.plot.ArbDepthCalculator;
import gov.usgs.plot.ArbDepthFrameRenderer;
import gov.usgs.plot.AxisRenderer;
import gov.usgs.plot.BasicFrameRenderer;
import gov.usgs.plot.Plot;
import gov.usgs.plot.PlotException;
import gov.usgs.plot.Renderer;
import gov.usgs.plot.ShapeRenderer;
import gov.usgs.plot.SmartTick;
import gov.usgs.plot.map.GeoImageSet;
import gov.usgs.plot.map.GeoLabelSet;
import gov.usgs.plot.map.MapRenderer;
import gov.usgs.proj.GeoRange;
import gov.usgs.proj.TransverseMercator;
import gov.usgs.util.Log;
import gov.usgs.util.Pool;
import gov.usgs.util.Util;
import gov.usgs.util.UtilException;
import gov.usgs.valve3.PlotComponent;
import gov.usgs.valve3.Plotter;
import gov.usgs.valve3.Valve3;
import gov.usgs.valve3.Valve3Exception;
import gov.usgs.valve3.result.Valve3Plot;
import gov.usgs.vdx.client.VDXClient;
import gov.usgs.vdx.data.Rank;
import gov.usgs.vdx.data.ExportData;
import gov.usgs.vdx.data.HistogramExporter;
import gov.usgs.vdx.data.hypo.Hypocenter;
import gov.usgs.vdx.data.hypo.HypocenterList;
import gov.usgs.vdx.data.hypo.HypocenterList.BinSize;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer.Axes;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer.ColorOption;
import gov.usgs.vdx.data.HypocenterExporter;
import gov.usgs.vdx.data.MatrixExporter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.RenderedImage;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cern.colt.matrix.DoubleMatrix2D;

/**
 * A class for making hypocenter map plots and histograms. 
 * 
 * TODO: display number of hypocenters on plot. 
 * TODO: implement triple view. 
 * TODO: implement arbitrary cross-sections. 
 * 
 * @author Dan Cervelli
 */
public class HypocenterPlotter extends RawDataPlotter {
	
	private enum PlotType {
		MAP, COUNTS;
		public static PlotType fromString(String s) {
			if (s.equals("map")) {
				return MAP;
			} else if (s.equals("cnts")) {
				return COUNTS;
			} else {
				return null;
			}
		}
	}

	private enum RightAxis {
		NONE(""), CUM_COUNTS("Cumulative Counts"), CUM_MAGNITUDE("Cumulative Magnitude"), CUM_MOMENT("Cumulative Moment");

		private String description;

		private RightAxis(String s) {
			description = s;
		}

		public String toString() {
			return description;
		}

		public static RightAxis fromString(String s) {
			switch (s.charAt(0)) {
			case 'N':
				return NONE;
			case 'C':
				return CUM_COUNTS;
			case 'M':
				return CUM_MAGNITUDE;
			case 'T':
				return CUM_MOMENT;
			default:
				return null;
			}
		}
	}

	private static final double DEFAULT_WIDTH = 100.0;

	private double hypowidth; // the width is for the Arbitrary line vs depth plot (SBH)
	private GeoRange range;
	private Point2D startLoc;
	private Point2D endLoc;
	private double minDepth, maxDepth;
	private double minMag, maxMag;
	private Integer minNPhases, maxNPhases;
	private double minRMS, maxRMS;
	private double minHerr, maxHerr;
	private double minVerr, maxVerr;
	private String rmk;
	
	private Axes axes;
	private ColorOption color;
	private PlotType plotType;
	private BinSize bin;
	private RightAxis rightAxis;
	private HypocenterList hypos;
	private DateFormat dateFormat;

	
	/**
	 * Default constructor
	 */
	public HypocenterPlotter() {
		super();
		dateFormat	= new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		logger = Log.getLogger("gov.usgs.valve3");
	}

	/**
	 * Initialize internal data from PlotComponent component
	 * 
	 * @param component PlotComponent
	 * @throws Valve3Exception
	 */
	protected void getInputs(PlotComponent component) throws Valve3Exception {
		
		rk = component.getInt("rk");
	
		endTime = component.getEndTime();
		if (Double.isNaN(endTime))
			throw new Valve3Exception("Illegal end time.");
		
		startTime = component.getStartTime(endTime);
		if (Double.isNaN(startTime))
			throw new Valve3Exception("Illegal start time.");
		
		String pt = component.get("plotType");
		if ( pt == null )
			plotType = PlotType.MAP;
		else {
			plotType	= PlotType.fromString(pt);
			if (plotType == null) {
				throw new Valve3Exception("Illegal plot type: " + pt);
			}
		}
		try{
			xTickMarks = component.getBoolean("xTickMarks");
		} catch(Valve3Exception e){
			xTickMarks=true;
		}
		try{
			xTickValues = component.getBoolean("xTickValues");
		} catch(Valve3Exception e){
			xTickValues=true;
		}
		try{
			xUnits = component.getBoolean("xUnits");
		} catch(Valve3Exception e){
			xUnits=true;
		}
		try{
			xLabel = component.getBoolean("xLabel");
		} catch(Valve3Exception e){
			xLabel=false;
		}
		try{
			yTickMarks = component.getBoolean("yTickMarks");
		} catch(Valve3Exception e){
			yTickMarks=true;
		}
		try{
			yTickValues = component.getBoolean("yTickValues");
		} catch(Valve3Exception e){
			yTickValues=true;
		}
		try{
			yUnits = component.getBoolean("yUnits");
		} catch(Valve3Exception e){
			yUnits=true;
		}
		try{
			yLabel = component.getBoolean("yLabel");
		} catch(Valve3Exception e){
			yLabel=false;
		}
		try{
			isDrawLegend = component.getBoolean("lg");
		} catch(Valve3Exception e){
			isDrawLegend=true;
		}
		
		double w = component.getDouble("west");
		if (w > 360 || w < -360)
			throw new Valve3Exception("Illegal area of interest: w=" +w);
		double e = component.getDouble("east");
		if (e > 360 || e < -360)
			throw new Valve3Exception("Illegal area of interest: e=" +e);
		double s = component.getDouble("south");
		if (s < -90)
			throw new Valve3Exception("Illegal area of interest: s=" +s);
		double n = component.getDouble("north");
		if (n > 90)
			throw new Valve3Exception("Illegal area of interest: n=" +n);
		

//		this.startLoc = new Point2D.Double(w,s);
//		this.endLoc = new Point2D.Double(e,n);
		this.startLoc = new Point2D.Double(w,n);
		this.endLoc = new Point2D.Double(e,s);
		
		if(s>=n){
			double t = s;
			s=n;
			n=t;
			//throw new Valve3Exception("Illegal area of interest: s=" + s + ", n=" + n);

		}	
		if(w>=e){
			double t = e;
			e=w;
			w=t;
			//throw new Valve3Exception("Illegal area of interest: s=" + s + ", n=" + n);
		}	
		range	= new GeoRange(w, e, s, n);
		
		hypowidth = Util.stringToDouble(component.get("hypowidth"), DEFAULT_WIDTH);
		
		minMag		= Util.stringToDouble(component.get("minMag"), -Double.MAX_VALUE);
		maxMag		= Util.stringToDouble(component.get("maxMag"), Double.MAX_VALUE);
		if (minMag > maxMag)
			throw new Valve3Exception("Illegal magnitude filter.");
		
		minDepth	= Util.stringToDouble(component.get("minDepth"), -Double.MAX_VALUE);
		maxDepth	= Util.stringToDouble(component.get("maxDepth"), Double.MAX_VALUE);
		if (minDepth > maxDepth)
			throw new Valve3Exception("Illegal depth filter.");
		
		minNPhases	= Util.stringToInteger(component.get("minNPhases"), Integer.MIN_VALUE);
		maxNPhases	= Util.stringToInteger(component.get("maxNPhases"), Integer.MAX_VALUE);
		if (minNPhases > maxNPhases)
			throw new Valve3Exception("Illegal nphases filter.");
		
		minRMS		= Util.stringToDouble(component.get("minRMS"), -Double.MAX_VALUE);
		maxRMS		= Util.stringToDouble(component.get("maxRMS"), Double.MAX_VALUE);
		if (minRMS > maxRMS)
			throw new Valve3Exception("Illegal RMS filter.");
		
		minHerr		= Util.stringToDouble(component.get("minHerr"), -Double.MAX_VALUE);
		maxHerr		= Util.stringToDouble(component.get("maxHerr"), Double.MAX_VALUE);
		if (minHerr > maxHerr)
			throw new Valve3Exception("Illegal horizontal error filter.");
		
		minVerr		= Util.stringToDouble(component.get("minVerr"), -Double.MAX_VALUE);
		maxVerr		= Util.stringToDouble(component.get("maxVerr"), Double.MAX_VALUE);
		if (minVerr > maxVerr)
			throw new Valve3Exception("Illegal vertical error filter.");
		
		rmk			= Util.stringToString(component.get("rmk"), "");
		String c = "";
		switch (plotType) {
		
		case MAP:			
			String axisType = component.get("axes");
			String a    = Util.stringToString(axisType, "M");
			axes		= Axes.fromString(a);
			if (axes == null)
				throw new Valve3Exception("Illegal axes type.");

			 c	= Util.stringToString(component.get("color"), "A");
			if (c.equals("A"))
				color	= ColorOption.chooseAuto(axes);
			else
				color	= ColorOption.fromString(c);
			if (color == null)
				throw new Valve3Exception("Illegal color option.");
			
			break;
		
		case COUNTS:
			String bs	= Util.stringToString(component.get("cntsBin"), "day");
			bin			= BinSize.fromString(bs);
			if (bin == null)
				throw new Valve3Exception("Illegal bin size option.");

			if ((endTime - startTime) / bin.toSeconds() > 10000)
				throw new Valve3Exception("Bin size too small.");

			String ra   = Util.stringToString(component.get("cntsAxis"), "C");
			rightAxis	= RightAxis.fromString(ra);
			if (rightAxis == null)
				throw new Valve3Exception("Illegal counts axis option.");
			
			break;
		}
	}

	/**
	 * Gets hypocenter list binary data from VDX
	 * 
	 * @param component PlotComponent
	 * @throws Valve3Exception
	 */
	protected void getData(PlotComponent component) throws Valve3Exception {
		
		double twest =range.getWest();
		double teast = range.getEast();
		double tsouth = range.getSouth();
		double tnorth = range.getNorth();
		
		Axes tmp = this.axes;
		if (axes == Axes.ARB_DEPTH || axes == Axes.ARB_TIME) {
			// we need to get extra hypocenters for plotting the width
			
			
			double latDiff = ArbDepthCalculator.getLatDiff(hypowidth);
			
			double lonNorthDiff = ArbDepthCalculator.getLonDiff(hypowidth, tnorth);
			double lonSouthDiff = ArbDepthCalculator.getLonDiff(hypowidth, tsouth);
			
			twest -= lonSouthDiff;
			teast += lonNorthDiff;
			tsouth -= latDiff;
			tnorth += latDiff;
		}
			
			
			
		// create a map of all the input parameters
		Map<String, String> params = new LinkedHashMap<String, String>();
		params.put("source", vdxSource);
		params.put("action", "data");
		params.put("st", Double.toString(startTime));
		params.put("et", Double.toString(endTime));
		params.put("rk", Integer.toString(rk));
		params.put("west", Double.toString(twest));
		params.put("east", Double.toString(teast));
		params.put("south", Double.toString(tsouth));
		params.put("north", Double.toString(tnorth));
		params.put("minDepth", Double.toString(-maxDepth));
		params.put("maxDepth", Double.toString(-minDepth));
		params.put("minMag", Double.toString(minMag));
		params.put("maxMag", Double.toString(maxMag));
		params.put("minNPhases", Integer.toString(minNPhases));
		params.put("maxNPhases", Integer.toString(maxNPhases));
		params.put("minRMS", Double.toString(minRMS));
		params.put("maxRMS", Double.toString(maxRMS));
		params.put("minHerr", Double.toString(minHerr));
		params.put("maxHerr", Double.toString(maxHerr));
		params.put("minVerr", Double.toString(minVerr));
		params.put("maxVerr", Double.toString(maxVerr));
		params.put("rmk", (rmk));

		// checkout a connection to the database
		Pool<VDXClient> pool	= Valve3.getInstance().getDataHandler().getVDXClient(vdxClient);
		VDXClient client		= pool.checkout();
		if (client == null)
			return;

		// get the data, if nothing is returned then create an empty list
		try{
			hypos = (HypocenterList) client.getBinaryData(params);
		}
		catch(UtilException e){
			throw new Valve3Exception(e.getMessage()); 
		}
		if (hypos == null)
			hypos = new HypocenterList();
		else
			hypos.adjustTime(component.getOffset(startTime));
		// check back in our connection to the database
		pool.checkin(client);
	}

	/**
	 * Initialize MapRenderer and add it to given plot 
	 * 
	 * @param plot Valve3Plot
	 * @param component PlotComponent
	 * @throws Valve3Exception
	 */
	private BasicFrameRenderer plotMapView(Plot plot, PlotComponent component) throws Valve3Exception {
		double timeOffset = component.getOffset(startTime);
		// TODO: make projection variable
		TransverseMercator proj = new TransverseMercator();
		Point2D.Double origin = range.getCenter();
		proj.setup(origin, 0, 0);

		hypos.project(proj);

		MapRenderer mr = new MapRenderer(range, proj);
		int mh = component.getInt("mh");
		mr.setLocationByMaxBounds(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), mh);

		GeoLabelSet labels = Valve3.getInstance().getGeoLabelSet();
		mr.setGeoLabelSet(labels.getSubset(range));

		GeoImageSet images = Valve3.getInstance().getGeoImageSet();
		RenderedImage ri = images.getMapBackground(proj, range, component.getBoxWidth());

		mr.setMapImage(ri);
		mr.createBox(8);
		mr.createGraticule(8, xTickMarks, yTickMarks, xTickValues, yTickValues, Color.BLACK);
		plot.setSize(plot.getWidth(), mr.getGraphHeight() + 60);
		double[] trans = mr.getDefaultTranslation(plot.getHeight());
		trans[4] = startTime+timeOffset;
		trans[5] = endTime+timeOffset;
		trans[6] = origin.x;
		trans[7] = origin.y;
		component.setTranslation(trans);
		component.setTranslationType("map");
		return mr;
	}

	/**
	 * 
	 * @return plot top label text
	 */
	private String getTopLabel(Rank rank) {
		StringBuilder top = new StringBuilder(100);
		top.append(hypos.size() + " " + rank.getName());
		if (hypos.size() == 1) {
			top.append(" earthquake on ");
			top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(0).j2ksec)));
		} else {
			top.append(" earthquakes");
			if (hypos.size() > 1) {
				top.append(" between ");
				top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(0).j2ksec)));
				top.append(" and ");
				top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(hypos.size() - 1).j2ksec)));
			}
		}
		return top.toString();
	}

	/**
	 * Initialize BasicFrameRenderer (init mode depends from axes type) and add it to plot.
	 * Generate PNG image to local file.
	 * @param v3Plot Valve3Plot
	 * @param component PlotComponent
	 * @param rank Rank
	 * @throws Valve3Exception
	 */
	private void plotMap(Valve3Plot v3Plot, PlotComponent component, Rank rank) throws Valve3Exception {
		ArbDepthCalculator adc = null;
		
		BasicFrameRenderer base = new BasicFrameRenderer();
		base.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
		double timeOffset = component.getOffset(startTime);
		String subCount = "";
		double lat1;
		double lon1;
		double lat2;
		double lon2;
		int count;
		List<Hypocenter> myhypos;
		switch (axes) {
		case MAP_VIEW:
			base = plotMapView(v3Plot.getPlot(), component);
			base.createEmptyAxis();
			if(xUnits){
				base.getAxis().setBottomLabelAsText("Longitude");
			}
			if(yUnits){
				base.getAxis().setLeftLabelAsText("Latitude");
			}
			((MapRenderer) base).createScaleRenderer();
			break;
		case LON_DEPTH:
			base.setExtents(range.getWest(), range.getEast(), -maxDepth, -minDepth);
			base.createDefaultAxis();
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("xy");
			base.getAxis().setBottomLabelAsText("Longitude");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		case LAT_DEPTH:
			base.setExtents(range.getSouth(), range.getNorth(), -maxDepth, -minDepth);
			base.createDefaultAxis();
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("xy");
			base.getAxis().setBottomLabelAsText("Latitude");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		case ARB_DEPTH:

		
			// need to set the extents for along the line. km offset?

			base = new ArbDepthFrameRenderer();
			base.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());

			/*
			 * 			lat1 = range.getSouth();
			 *			lon1 = range.getWest();
			 *			lat2 = range.getNorth();
			 *			lon2 = range.getEast();
			 */

			lat1 = startLoc.getY();
			lon1 = startLoc.getX();
			lat2 = endLoc.getY();
			lon2 = endLoc.getX();

			adc = new ArbDepthCalculator(lat1, lon1, lat2, lon2, hypowidth);

			((ArbDepthFrameRenderer)base).setArbDepthCalc(adc);

			//			base.setExtents(range.getSouth(), range.getNorth(), -maxDepth, -minDepth);
			base.setExtents(0.0, adc.getMaxDist(), -maxDepth, -minDepth);

			base.createDefaultAxis();
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("xy");
			base.getAxis().setBottomLabelAsText("Distance (km) from (" + lat1 + "," + lon1 +") to (" + lat2 + "," + lon2 +") - width = " +hypowidth + " km");
			base.getAxis().setLeftLabelAsText("Depth (km)");
		
			count = 0;
			myhypos = hypos.getHypocenters();
			for (int i = 0; i < myhypos.size(); i++) {	
				
				Hypocenter hc = (Hypocenter) myhypos.get(i);
				if (adc.isInsideArea(hc.lat, hc.lon)) {
					count++;
				}
			}
			subCount = new String(count + " of ");
			
			break;
		case DEPTH_TIME:
			base.setExtents(startTime+timeOffset, endTime+timeOffset, -maxDepth, -minDepth);
			base.createDefaultAxis();
			base.setXAxisToTime(8);
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("ty");
			base.getAxis().setBottomLabelAsText("Time");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		case ARB_TIME:
			
			// need to set the extents for along the line. km offset?

			int mh = component.getInt("mh");
			

			
			base = new ArbDepthFrameRenderer();
			//base.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
			base.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), mh);
			
			v3Plot.getPlot().setSize(v3Plot.getPlot().getWidth(), mh + 60);
			
			lat1 = startLoc.getY();
			lon1 = startLoc.getX();
			lat2 = endLoc.getY();
			lon2 = endLoc.getX();

			adc = new ArbDepthCalculator(lat1, lon1, lat2, lon2, hypowidth);

			((ArbDepthFrameRenderer)base).setArbDepthCalc(adc);

		
		
			count = 0;
			myhypos = hypos.getHypocenters();
			for (int i = 0; i < myhypos.size(); i++) {	
				
				Hypocenter hc = (Hypocenter) myhypos.get(i);
				if (adc.isInsideArea(hc.lat, hc.lon)) {
					count++;
				}
			}
			subCount = new String(count + " of ");
					
			//base.setExtents(0.0, adc.getMaxDist(), -maxDepth, -minDepth);

			base.setExtents(startTime+timeOffset, endTime+timeOffset, 0.0, adc.getMaxDist());
			base.createDefaultAxis();
			base.setXAxisToTime(8);
			
					
			base.getAxis().setLeftLabelAsText("Distance (km) from (" + lat1 + "," + lon1 +") to (" + lat2 + "," + lon2 +") - width = " +hypowidth + " km");
						
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("ty");
			base.getAxis().setBottomLabelAsText("Time");
			
			break;
		}
						
		base.getAxis().setTopLabelAsText(subCount + getTopLabel(rank));   // set the label at the top of the plot.
		v3Plot.getPlot().addRenderer(base);                    // add my framerenderer to this plot
		
		HypocenterRenderer hr = new HypocenterRenderer(hypos, base, axes);
		hr.setColorOption(color);
		if (color == ColorOption.TIME)
			hr.setColorTime(startTime+timeOffset, endTime+timeOffset);
		hr.createColorScaleRenderer(base.getGraphX() + base.getGraphWidth() + 16, base.getGraphY() + base.getGraphHeight());
		hr.createMagnitudeScaleRenderer(base.getGraphX() + base.getGraphWidth() + 16, base.getGraphY());
		v3Plot.getPlot().addRenderer(hr);

		v3Plot.addComponent(component);
	}

	/**
	 * If v3Plot is null, prepare data for exporting
	 * Otherwise, initialize HistogramRenderer and add it to plot.
	 * 		Generate PNG image to local file.
	 * @param v3Plot Valve3Plot
	 * @param component PlotComponent
	 * @param rank Rank
	 * @throws Valve3Exception 
	 */
	private void plotCounts(Valve3Plot v3Plot, PlotComponent component, Rank rank) throws Valve3Exception {
		int leftLabels = 0;
		
		boolean      forExport = (v3Plot == null);	// = "prepare data for export"
		double timeOffset = component.getOffset(startTime);
		HistogramExporter hr = new HistogramExporter(hypos.getCountsHistogram(bin));
		hr.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
		hr.setDefaultExtents();
		hr.setMinX(startTime+timeOffset);
		hr.setMaxX(endTime+timeOffset);
		hr.createDefaultAxis(8,8,xTickMarks,yTickMarks, false, true, xTickValues, yTickValues);
		hr.setXAxisToTime(8, xTickMarks, xTickValues);
		if(yUnits){
			hr.getAxis().setLeftLabelAsText("Earthquakes per " + bin);
		}
		if(xUnits){
			hr.getAxis().setBottomLabelAsText(component.getTimeZone().getID() + " Time (" + Util.j2KToDateString(startTime+timeOffset, "yyyy-MM-dd HH:mm:ss") + " to " + Util.j2KToDateString(endTime+timeOffset, "yyyy-MM-dd HH:mm:ss")+ ")");
		}
		if(xLabel){
			hr.getAxis().setTopLabelAsText(getTopLabel(rank));
		}
		if(hr.getAxis().getLeftLabels() != null){
			leftLabels = hr.getAxis().getLeftLabels().length;
		}

		if ( forExport ) {
			// Add column headers to csvHdrs (second one incomplete)
			csvHdrs.append(String.format( ",%s_EventsPer%s", rank.getName(), bin ));
			csvData.add( new ExportData( csvIndex, hr ) );
			csvIndex++;
		}
		DoubleMatrix2D data = null;
		String headerFmt = "";
		switch (rightAxis) {
		case CUM_COUNTS:
			data = hypos.getCumulativeCounts();
			if ( forExport )
				// Add specialized part of column header to csvText
				headerFmt = ",%s_CumulativeCounts";
			break;
		case CUM_MAGNITUDE:
			data = hypos.getCumulativeMagnitude();
			if ( forExport )
				// Add specialized part of column header to csvText
				headerFmt = ",%s_CumulativeMagnitude";
			break;
		case CUM_MOMENT:
			data = hypos.getCumulativeMoment();
			if ( forExport )
				// Add specialized part of column header to csvText
				headerFmt = ",%s_CumulativeMoment";
			break;
		}
		if (data != null && data.rows() > 0) {
			double cmin = data.get(0, 1);
			double cmax = data.get(data.rows() - 1, 1);	
			
			// TODO: utilize ranks for counts plots
			MatrixExporter mr = new MatrixExporter(data, false, null);
			mr.setAllVisible(true);
			mr.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
			mr.setExtents(startTime+timeOffset, endTime+timeOffset, cmin, cmax * 1.05);
			mr.createDefaultLineRenderers(component.getColor());
			
			if ( forExport ) {
				// Add coulmn to header; add Exporter to set for CSV
				csvHdrs.append(String.format( headerFmt, rank.getName() ));
				csvData.add( new ExportData( csvIndex, mr ) );
				csvIndex++;
			} else {
				Renderer[] r = mr.getLineRenderers();
				((ShapeRenderer)r[0]).color		= Color.red;
				((ShapeRenderer)r[0]).stroke	= new BasicStroke(2.0f);
				AxisRenderer ar = new AxisRenderer(mr);
				if(yTickValues){
					ar.createRightTickLabels(SmartTick.autoTick(cmin, cmax, leftLabels, false), null);
				}
				mr.setAxis(ar);
			
				hr.addRenderer(mr);
				if(yUnits){
					hr.getAxis().setRightLabelAsText(rightAxis.toString());
				}
			}
		}
		
		if ( forExport )
			return;
		if(isDrawLegend) hr.createDefaultLegendRenderer(new String[] {rank.getName() + " Events"});
		
		component.setTranslation(hr.getDefaultTranslation(v3Plot.getPlot().getHeight()));
		component.setTranslationType("ty");
		v3Plot.getPlot().addRenderer(hr);
		v3Plot.addComponent(component);	
	}
	
	/**
	 * Compute rank, calls appropriate function to init renderers
	 * @param v3Plot Valve3Plot
	 * @param component PlotComponent
	 * @throws Valve3Exception
	 */
	public void plotData(Valve3Plot v3Plot, PlotComponent component) throws Valve3Exception {
		boolean     forExport = (v3Plot == null);	// = "prepare data for export"
		
		// setup the display for the legend
		Rank rank	= new Rank();
		if (rk == 0) {
			rank	= rank.bestPossible();
			if ( !forExport )
				v3Plot.setExportable( false );
			else
				throw new Valve3Exception( "Exports for Best Possible Rank not allowed" );
		} else {
			rank	= ranksMap.get(rk);
		}
		
		switch (plotType) {
		case MAP:
			if ( forExport ) {
				csvHdrs.append(", Lat, Lon, Depth, PrefMag");
				csvData.add(new ExportData(csvIndex, new HypocenterExporter(
						hypos)));
				csvIndex++;
			} else {
				plotMap(v3Plot, component, rank);
				v3Plot.setTitle(Valve3.getInstance().getMenuHandler().getItem(
						vdxSource).name
						+ " Map");
			}
			break;
		
		case COUNTS:
			plotCounts(v3Plot, component, rank);
			if ( !forExport )
				v3Plot.setTitle(Valve3.getInstance().getMenuHandler().getItem(vdxSource).name + " Counts");
			break;			
		}			
	}

	/**
	 * Concrete realization of abstract method. 
	 * Generate PNG image (hypocenters map or histogram, depends on plot type) to file with random name.
	 * If v3p is null, prepare data for export -- assumes csvData, csvData & csvIndex initialized
	 * @param v3p Valve3Plot
	 * @param comp PlotComponent
	 * @throws Valve3Exception
	 * @see Plotter
	 */
	public void plot(Valve3Plot v3p, PlotComponent comp) throws Valve3Exception, PlotException {
		forExport = (v3p == null);	// = "prepare data for export"
		ranksMap	= getRanks(vdxSource, vdxClient);
		getInputs(comp);
		getData(comp);

		plotData(v3p, comp);
				
		if ( !forExport ) {
			Plot plot = v3p.getPlot();
			plot.setBackgroundColor(Color.white);
			plot.writePNG(v3p.getLocalFilename());
		}
	}
}
