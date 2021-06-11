package org.osm2world.core.world.modules.building;

import static java.lang.Math.max;
import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;
import static org.osm2world.core.math.GeometryUtil.interpolateBetween;
import static org.osm2world.core.math.SimplePolygonXZ.asSimplePolygon;
import static org.osm2world.core.math.algorithms.TriangulationUtil.triangulate;
import static org.osm2world.core.target.common.material.NamedTexCoordFunction.STRIP_WALL;
import static org.osm2world.core.target.common.material.TexCoordUtil.*;
import static org.osm2world.core.world.modules.building.WindowParameters.WindowRegion.*;
import static org.osm2world.core.world.modules.common.WorldModuleGeometryUtil.createTriangleStripBetween;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.osm2world.core.math.Angle;
import org.osm2world.core.math.AxisAlignedRectangleXZ;
import org.osm2world.core.math.LineSegmentXZ;
import org.osm2world.core.math.PolygonXYZ;
import org.osm2world.core.math.SimplePolygonXZ;
import org.osm2world.core.math.TriangleXYZ;
import org.osm2world.core.math.TriangleXZ;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.math.algorithms.JTSBufferUtil;
import org.osm2world.core.math.shapes.PolylineShapeXZ;
import org.osm2world.core.math.shapes.PolylineXZ;
import org.osm2world.core.math.shapes.ShapeXZ;
import org.osm2world.core.math.shapes.SimpleClosedShapeXZ;
import org.osm2world.core.math.shapes.SimplePolygonShapeXZ;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.ExtrudeOption;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.world.modules.building.WindowParameters.RegionProperties;
import org.osm2world.core.world.modules.building.WindowParameters.WindowRegion;

public class GeometryWindow implements Window {

	private static final double DEPTH = 0.10;
	private static final double OUTER_FRAME_WIDTH = 0.1;
	private static final double INNER_FRAME_WIDTH = 0.05;
	private static final double OUTER_FRAME_THICKNESS = 0.05;
	private static final double INNER_FRAME_THICKNESS = 0.03;

	private final WindowParameters params;

	private final SimpleClosedShapeXZ outline;
	private final SimpleClosedShapeXZ paneOutline;
	private final List<PolylineShapeXZ> innerFramePaths;

	private final boolean transparent;

	public GeometryWindow(VectorXZ position, WindowParameters params, boolean transparent) {

		this.params = params;
		this.transparent = transparent;

		/* build the outline, either as a simple shape or as a combination of multiple window regions */

		Map<WindowRegion, LineSegmentXZ> regionBorderSegments = new EnumMap<>(WindowRegion.class);
		Map<WindowRegion, SimpleClosedShapeXZ> regionOutlines = new EnumMap<>(WindowRegion.class);

		// TODO: implement the other regions: LEFT, RIGHT and BOTTOM
		boolean useRegions = params.regionProperties.containsKey(CENTER) && params.regionProperties.containsKey(TOP);

		if (!useRegions) {

			outline = params.overallProperties.shape.buildShapeXZ(position, params.width, params.height);

		} else {

			regionOutlines.put(CENTER,
					params.regionProperties.get(CENTER).shape.buildShapeXZ(position, params.width, params.height));
			SimpleClosedShapeXZ centerOutline = regionOutlines.get(CENTER);

			LineSegmentXZ topSegment = centerOutline.intersectionSegments(new LineSegmentXZ(
					centerOutline.getCentroid(), centerOutline.getCentroid().add(0, 1000)))
					.stream().findAny().get();
			regionBorderSegments.put(TOP, topSegment);

			RegionProperties properties = params.regionProperties.get(TOP);

			double topHeight = 1.0; //TODO use parameters for region height and width
			regionOutlines.put(TOP, properties.shape.buildShapeXZ(topSegment, topHeight));

			assert !centerOutline.isClockwise();
			assert !regionOutlines.get(TOP).isClockwise();

			List<VectorXZ> newOutline = new ArrayList<>();

			for (int i = centerOutline.vertices().indexOf(topSegment.p2);
					i != centerOutline.vertices().indexOf(topSegment.p1);
					i = (i + 1) % centerOutline.vertices().size()) {
				newOutline.add(centerOutline.vertices().get(i));
			}

			VectorXZ start = regionOutlines.get(TOP).vertices().stream().min(
					Comparator.comparingDouble(topSegment.p1::distanceTo)).get();
			VectorXZ end = regionOutlines.get(TOP).vertices().stream().min(
					Comparator.comparingDouble(topSegment.p2::distanceTo)).get();

			for (int i = regionOutlines.get(TOP) .vertices().indexOf(start);
					i != regionOutlines.get(TOP).vertices().indexOf(end);
					i = (i + 1) % regionOutlines.get(TOP).vertices().size()) {
				newOutline.add(regionOutlines.get(TOP).vertices().get(i));
			}

			newOutline.add(newOutline.get(0));

			outline = new SimplePolygonXZ(newOutline);

		}

		/* calculate the border for the actual glass pane */

		paneOutline = paneOutlineFromOutline(outline);

		/* place borders */

		if (params.overallProperties.panes != null) {

			int panesVertical = params.overallProperties.panes.panesVertical;
			int panesHorizontal = params.overallProperties.panes.panesHorizontal;

			innerFramePaths = (params.overallProperties.panes.radialPanes)
					? innerPaneBorderPathsRadial(paneOutline, null, panesHorizontal, panesVertical)
					: innerPaneBorderPaths(paneOutline, panesHorizontal, panesVertical);

		} else if (!regionOutlines.isEmpty()) {

			innerFramePaths = new ArrayList<>();

			for (WindowRegion region : regionOutlines.keySet()) {

				RegionProperties properties = params.regionProperties.get(region);

				if (properties != null) {

					SimpleClosedShapeXZ regionPaneOutline = paneOutlineFromOutline(regionOutlines.get(region));

					innerFramePaths.addAll((properties.panes.radialPanes)
							? innerPaneBorderPathsRadial(regionPaneOutline, regionBorderSegments.get(region),
									properties.panes.panesHorizontal, properties.panes.panesVertical)
							: innerPaneBorderPaths(regionPaneOutline,
									properties.panes.panesHorizontal, properties.panes.panesVertical));

				}

			}

			innerFramePaths.addAll(regionBorderSegments.values());

		} else {
			innerFramePaths = emptyList();
		}

	}

	private static SimpleClosedShapeXZ paneOutlineFromOutline(SimpleClosedShapeXZ outline) {
		// scaling is a "good enough" simplification; polygon buffering would be accurate
		double scaleFactor = max(0.1, 1 - (INNER_FRAME_WIDTH / outline.getDiameter()));
		return outline.scale(scaleFactor);
	}

	private static List<PolylineShapeXZ> innerPaneBorderPaths(SimpleClosedShapeXZ paneOutline,
			int panesHorizontal, int panesVertical) {

		List<PolylineShapeXZ> result = new ArrayList<>();

		AxisAlignedRectangleXZ paneBbox = paneOutline.boundingBox();

		VectorXZ windowBottom = paneBbox.center().add(0, -paneBbox.sizeZ() / 2);
		VectorXZ windowTop = paneBbox.center().add(0, +paneBbox.sizeZ() / 2);
		for (int vertFrameI = 0; vertFrameI < panesVertical - 1; vertFrameI ++) {
			VectorXZ center = interpolateBetween(windowBottom, windowTop, (vertFrameI + 1.0)/panesVertical);
			LineSegmentXZ intersectionSegment = new LineSegmentXZ(
					center.add(-paneBbox.sizeX(), 0), center.add(+paneBbox.sizeX(), 0));
			List<VectorXZ> is = paneOutline.intersectionPositions(intersectionSegment);
			result.add(new LineSegmentXZ(
					Collections.min(is, Comparator.comparingDouble(v -> v.x)),
					Collections.max(is, Comparator.comparingDouble(v -> v.x))));
		}

		VectorXZ windowLeft = paneBbox.center().add(-paneBbox.sizeX() / 2, 0);
		VectorXZ windowRight = paneBbox.center().add(+paneBbox.sizeX() / 2, 0);
		for (int horizFrameI = 0; horizFrameI < panesHorizontal - 1; horizFrameI ++) {
			VectorXZ center = interpolateBetween(windowLeft, windowRight, (horizFrameI + 1.0)/panesHorizontal);
			LineSegmentXZ intersectionSegment = new LineSegmentXZ(
					center.add(0, -paneBbox.sizeZ()), center.add(0, +paneBbox.sizeZ()));
			List<VectorXZ> is = paneOutline.intersectionPositions(intersectionSegment);
			result.add(new LineSegmentXZ(
					Collections.min(is, Comparator.comparingDouble(v -> v.z)),
					Collections.max(is, Comparator.comparingDouble(v -> v.z))));
		}

		return result;

	}

	@SuppressWarnings("unused") // TODO use panesVertical for "rings" around the center
	private static List<PolylineShapeXZ> innerPaneBorderPathsRadial(SimpleClosedShapeXZ paneOutline,
			LineSegmentXZ regionBorderSegment, int panesHorizontal, int panesVertical) {

		List<PolylineShapeXZ> result = new ArrayList<>();

		VectorXZ center = paneOutline.getCentroid();
		if (regionBorderSegment != null) {
			center = regionBorderSegment.getCenter();
		}

		Angle minAngle, step;

		if (regionBorderSegment == null) {
			minAngle = Angle.ofDegrees(0);
			step = Angle.ofDegrees(360 / panesHorizontal);
		} else {
			// TODO: to support other regions than TOP, use regionBorderSegment's direction
			minAngle = Angle.ofDegrees(270);
			Angle maxAngle = Angle.ofDegrees(90);
			step = maxAngle.minus(minAngle).div(panesHorizontal);
		}

		for (int paneH = 0; paneH < panesHorizontal; paneH ++) {
			if (paneH > 0 || regionBorderSegment == null) {
				Angle angle = minAngle.plus(step.times(paneH));
				System.out.println(angle);
				LineSegmentXZ intersectionSegment = new LineSegmentXZ(center,
						center.add(VectorXZ.fromAngle(angle).mult(2 * paneOutline.getDiameter())));
				VectorXZ intersectionPoint = paneOutline.intersectionPositions(intersectionSegment).get(0);
				result.add(new PolylineXZ(center, intersectionPoint));
			}
		}

		return result;

	}

	@Override
	public SimplePolygonXZ outline() {
		return asSimplePolygon(outline);
	}

	@Override
	public Double insetDistance() {
		return DEPTH - OUTER_FRAME_THICKNESS;
	}

	@Override
	public void renderTo(Target target, WallSurface surface) {

		VectorXYZ windowNormal = surface.normalAt(outline().getCentroid());

		VectorXYZ toBack = windowNormal.mult(-DEPTH);
		VectorXYZ toOuterFrame = windowNormal.mult(-DEPTH + OUTER_FRAME_THICKNESS);

		/* draw the window pane */

		Material paneMaterial = transparent ? params.transparentWindowMaterial : params.opaqueWindowMaterial;

		List<TriangleXZ> paneTrianglesXZ = paneOutline.getTriangulation();
		List<TriangleXYZ> paneTriangles = paneTrianglesXZ.stream()
				.map(t -> surface.convertTo3D(t).shift(toBack))
				.collect(toList());
		target.drawTriangles(paneMaterial, paneTriangles,
				triangleTexCoordLists(paneTriangles, paneMaterial, surface::texCoordsGlobal));

		/* draw outer frame */

		SimplePolygonXZ outlinePolygon = asSimplePolygon(outline);

		List<SimplePolygonShapeXZ> innerOutlines = JTSBufferUtil.bufferPolygon(outlinePolygon, -OUTER_FRAME_WIDTH)
				.stream().map(p -> p.getOuter()).collect(toList());

		List<TriangleXZ> frontFaceTriangles = triangulate(outlinePolygon, innerOutlines);
		List<TriangleXYZ> frontFaceTrianglesXYZ = frontFaceTriangles.stream()
				.map(t -> surface.convertTo3D(t))
				.map(t -> t.shift(toOuterFrame))
				.collect(toList());
		target.drawTriangles(params.frameMaterial, frontFaceTrianglesXYZ,
				triangleTexCoordLists(frontFaceTrianglesXYZ, params.frameMaterial, surface::texCoordsGlobal));

		Material frameSideMaterial = params.frameMaterial;
		if (params.overallProperties.shape == WindowParameters.WindowShape.CIRCLE) {
			frameSideMaterial = frameSideMaterial.makeSmooth();
		}

		for (SimplePolygonShapeXZ innerOutline : innerOutlines) {
			PolygonXYZ innerOutlineXYZ = surface.convertTo3D(innerOutline);
			List<VectorXYZ> vsFrameSideStrip = createTriangleStripBetween(
					innerOutlineXYZ.add(toOuterFrame).vertices(),
					innerOutlineXYZ.add(toBack).vertices());
			target.drawTriangleStrip(frameSideMaterial, vsFrameSideStrip,
					texCoordLists(vsFrameSideStrip, frameSideMaterial, STRIP_WALL));
		}

		/* draw inner frame elements with shape extrusion along paths */

		ShapeXZ innerFrameShape = new AxisAlignedRectangleXZ(
				-INNER_FRAME_WIDTH/2, -INNER_FRAME_THICKNESS/2,
				+INNER_FRAME_WIDTH/2, +INNER_FRAME_THICKNESS/2);

		for (PolylineShapeXZ framePath : innerFramePaths) {

			List<VectorXYZ> framePathXYZ = framePath.vertices().stream()
					.map(v -> surface.convertTo3D(v))
					.map(v -> v.add(toBack))
					.collect(toList());

			target.drawExtrudedShape(params.frameMaterial, innerFrameShape,
					framePathXYZ, nCopies(framePathXYZ.size(), windowNormal),
					null, null, EnumSet.noneOf(ExtrudeOption.class));

		}

	}

}
