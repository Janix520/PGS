package micycle.pts;

import java.util.ArrayList;
import java.util.Arrays;

import org.geotools.geometry.jts.JTS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.triangulate.ConformingDelaunayTriangulator;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.IncrementalDelaunayTriangulator;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.locationtech.jts.util.GeometricShapeFactory;

import processing.core.PConstants;
import processing.core.PShape;
import processing.core.PVector;

/**
 * PTS | Processing Topology Suite
 * 
 * http://thecloudlab.org/processing/library.html
 * https://github.com/Rogach/jopenvoronoi https://github.com/IGNF/CartAGen
 * https://ignf.github.io/CartAGen/docs/algorithms/others/spinalize.html
 * https://github.com/twak/campskeleton
 * https://discourse.processing.org/t/straight-skeleton-or-how-to-draw-a-center-line-in-a-polygon-or-shape/17208/9
 * http://lastresortsoftware.blogspot.com/2010/12/smooth-as.html#note1
 * 
 * @author MCarleton
 *
 */
public class PTS implements PConstants {

	/**
	 * TODO GROUP, PRIMITIVE, PATH, or GEOMETRY TODO CACHE recent 5 calls?
	 * 
	 * @param shape
	 * @return
	 */
	public static Polygon fromPShape(PShape shape) {

//		shape.getKind() // switch to get primitive, then == ELLIPSE
		if (shape.getFamily() == PShape.PRIMITIVE) {
			GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
			shapeFactory.setNumPoints(40);
			switch (shape.getKind()) {
				case ELLIPSE:
					shapeFactory.setCentre(new Coordinate(shape.getParam(0), shape.getParam(1)));
					shapeFactory.setWidth(shape.getParam(2));
					shapeFactory.setHeight(shape.getParam(3));
					return shapeFactory.createEllipse();
				case TRIANGLE:
//					shapeFactor
					// TODO
					break;
				case QUAD:
					// TODO
					break;
				case RECT:
					// TODO
					break;
//				      * @param a x-coordinate of the ellipse
//				      * @param b y-coordinate of the ellipse
//				      * @param c width of the ellipse by default
//				      * @param d height of the ellipse by default
//					break;

				default:
					System.err.print("Primitive Shape" + shape.getKind() + " not implmented");
					return null;
			}
		}

		// GEOMETRY PShape types:

		final int[] contourGroups = getContourGroups(shape.getVertexCodes());
		final int[] vertexCodes = getVertexTypes(shape);

		final ArrayList<ArrayList<Coordinate>> coords = new ArrayList<>(); // list of coords representing rings

		int lastGroup = -1;

		for (int i = 0; i < shape.getVertexCount(); i++) {
			if (contourGroups[i] != lastGroup) {
				lastGroup = contourGroups[i];
				coords.add(new ArrayList<>());
			}

			/**
			 * Sample bezier curves at intervals to produce smooth Geometry
			 */
			switch (vertexCodes[i]) {

				case QUADRATIC_VERTEX:
					coords.get(lastGroup).addAll(getQuadraticBezierPoints(shape.getVertex(i - 1), shape.getVertex(i),
							shape.getVertex(i + 1), 20));
					i += 1;
					continue;

				case BEZIER_VERTEX: // aka cubic bezier, untested
					coords.get(lastGroup).addAll(getCubicBezierPoints(shape.getVertex(i - 1), shape.getVertex(i),
							shape.getVertex(i + 1), shape.getVertex(i + 2), 20));
					i += 2;
					continue;

				default:
					coords.get(lastGroup).add(new Coordinate(shape.getVertexX(i), shape.getVertexY(i)));
					break;
			}
		}

		for (ArrayList<Coordinate> contour : coords) {
			contour.add(contour.get(0)); // Points of LinearRing must form a closed linestring
		}

		final Coordinate[] outerCoords = new Coordinate[coords.get(0).size()];
		Arrays.setAll(outerCoords, coords.get(0)::get);

		GeometryFactory geometryFactory = new GeometryFactory();
		LinearRing outer = geometryFactory.createLinearRing(outerCoords);

		/**
		 * Create linear ring for each hole in the shape
		 */
		LinearRing[] holes = new LinearRing[coords.size() - 1];

		for (int j = 1; j < coords.size(); j++) {
			final Coordinate[] innerCoords = new Coordinate[coords.get(j).size()];
			Arrays.setAll(innerCoords, coords.get(j)::get);
			holes[j - 1] = geometryFactory.createLinearRing(innerCoords);
		}

		return geometryFactory.createPolygon(outer, holes);
	}

	/**
	 * broken for P2D (due to createshape)
	 * 
	 * @param polygon
	 * @return
	 */
	public static PShape toPShape(Polygon polygon) {
		PShape shape = new PShape();
		shape.setFamily(PShape.GEOMETRY);
		shape.setFill(true);
		shape.setFill(255);

		shape.beginShape();

		/**
		 * Draw one outer ring. Both inner and outer loops used to iterate upto length
		 * -1 to skip the point that closes the JTS shape (same as the first point).
		 * However calling buffer() produces broken results for some shapes.
		 */
		for (int i = 0; i < polygon.getExteriorRing().getCoordinates().length; i++) {
			// -1: ignore last coord (a copy of the first)
			Coordinate coord = polygon.getExteriorRing().getCoordinates()[i];
			shape.vertex((float) coord.x, (float) coord.y);
		}

		/**
		 * Draw any Contours/inner rings
		 */
		for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
			shape.beginContour();
			for (int i = 0; i < polygon.getInteriorRingN(j).getCoordinates().length; i++) {
				// -1: ignore last coord (a copy of the first)
				Coordinate coord = polygon.getInteriorRingN(j).getCoordinates()[i];
				shape.vertex((float) coord.x, (float) coord.y);
			}
			shape.endContour();
		}
		shape.endShape(CLOSE);

		return shape;
	}

	/**
	 * A geometry may include multiple geometries, so resulting PShape may contain
	 * multiple children.
	 * 
	 * @return
	 */
	public static PShape toPShape(Geometry geometry) {
		if (geometry.getNumGeometries() == 1) {
			return toPShape((Polygon) geometry);
		} else {
			PShape parent = new PShape(GROUP);
			for (int i = 0; i < geometry.getNumGeometries(); i++) {
				PShape child = toPShape((Polygon) geometry.getGeometryN(i));
				child.setFill(-16711936); // TODO
				parent.addChild(child);
			}
			return parent;
		}
	}

	public static void triangulate(PShape shape) {
//		fromPShape(shape).getExteriorRing().get
//		org.locationtech.jts.triangulate.DelaunayTriangulationBuilder.
	}

	/**
	 * 
	 * @param shape
	 * @param fit   0...1
	 * @return
	 */
	public static Polygon smooth(Polygon shape, float fit) {
		return (Polygon) JTS.smooth(shape, fit);
	}

	public static Geometry smooth(Geometry shape, float fit) {
		return JTS.smooth(shape, fit);
	}

	/**
	 * @return shape A - shape B
	 */
	public static PShape difference(PShape a, PShape b) {
		return toPShape(fromPShape(a).difference(fromPShape(b)));
	}

	/**
	 * @return A∪B - A∩B
	 */
	public static PShape symDifference(PShape a, PShape b) {
		return toPShape(fromPShape(a).symDifference(fromPShape(b)));
	}

	/**
	 * @return A∩B
	 */
	public static PShape intersection(PShape a, PShape b) {
		return toPShape(fromPShape(a).intersection(fromPShape(b)));
	}

	/**
	 * @return A∪B
	 */
	public static PShape union(PShape a, PShape b) {
		return toPShape(fromPShape(a).union(fromPShape(b)));
	}

	public static PShape buffer(PShape shape, float buffer) {
		return toPShape(fromPShape(shape).buffer(buffer, 4));
	}

	/**
	 * Simplifies a PShape using the Douglas-Peucker algorithm.
	 * 
	 * @param shape
	 * @param distanceTolerance
	 * @return
	 */
	public static PShape simplify(PShape shape, float distanceTolerance) {
		return toPShape(DouglasPeuckerSimplifier.simplify(fromPShape(shape), distanceTolerance));
	}

	/**
	 * Smoothes a geometry
	 * 
	 * @param shape
	 * @param buffer 0...1
	 * @return
	 */
	public static PShape smooth(PShape shape, float fit) {
		return toPShape(smooth(fromPShape(shape), fit));
	}

	public static PShape convexHull(PShape shape) {
		return toPShape(fromPShape(shape).convexHull());
	}

	/**
	 * Calc from vertices of PShape
	 * 
	 * @param shape
	 * @param tolerance
	 * @return
	 */
	public static PShape delaunayTriangulation(PShape shape, float tolerance) {
		Geometry g = fromPShape(shape);
		DelaunayTriangulationBuilder d = new DelaunayTriangulationBuilder();
		d.setTolerance(tolerance);
		d.setSites(g);
		Geometry out = d.getTriangles(geometryFactory);
		return toPShape(out);
	}

	/**
	 * 
	 * @param points
	 * @param tolerance default is 10, snaps pixels to the nearest N. higher values
	 *                  result in more coarse triangulation
	 * @return A PShape whose children are triangles making up the triangulation
	 */
	public static PShape delaunayTriangulation(ArrayList<PVector> points, float tolerance) {
		ArrayList<Coordinate> coords = new ArrayList<>(points.size());
		for (PVector p : points) {
			coords.add(new Coordinate(p.x, p.y));
		}
		/**
		 * Use ConformingDelau... for better result? (get constrained segments from edge
		 * ring) Code example: https://github.com/locationtech/jts/issues/320
		 */
		DelaunayTriangulationBuilder d = new DelaunayTriangulationBuilder();
		d.setTolerance(tolerance);
		d.setSites(coords);
		Geometry out = d.getTriangles(geometryFactory);
		return toPShape(out);
	}

	/**
	 * TODO set clip envelope?
	 * 
	 * @param shape
	 * @param tolerance
	 * @return
	 */
	public static PShape voronoiDiagram(PShape shape, float tolerance) {
		Geometry g = fromPShape(shape);
		VoronoiDiagramBuilder v = new VoronoiDiagramBuilder();
		v.setTolerance(tolerance);
		v.setSites(g);
		Geometry out = v.getDiagram(geometryFactory);
		return toPShape(out);
	}

	public static PShape voronoiDiagram(ArrayList<PVector> points, float tolerance) {
		ArrayList<Coordinate> coords = new ArrayList<>(points.size());
		for (PVector p : points) {
			coords.add(new Coordinate(p.x, p.y));
		}
		VoronoiDiagramBuilder v = new VoronoiDiagramBuilder();
		v.setTolerance(tolerance);
//		v.setClipEnvelope(new Envelope(0, 0, 500, 500));
		v.setSites(coords);
		Geometry out = v.getDiagram(geometryFactory);
		return toPShape(out);
	}

	/**
	 * TODO: outline of exterior ring only
	 * 
	 * @param shape
	 * @param points
	 * @param offsetDistance the distance the point is offset from the
	 *                       segment(positive is to the left, negative is to the
	 *                       right)
	 * @return
	 * @see #equidistantOutlineByDistance(PShape, float, float)
	 */
	public static PVector[] equidistantOutline(PShape shape, int points, float offsetDistance) {
		Polygon p = fromPShape(shape);

		LengthIndexedLine l = new LengthIndexedLine(p.getExteriorRing());
		PVector[] outlinePoints = new PVector[points * (p.getNumInteriorRing() + 1)];
		// exterior ring
		for (int i = 0; i < points; i++) {
			Coordinate q = l.extractPoint((i / (float) points) * l.getEndIndex(), offsetDistance);
			outlinePoints[i] = new PVector((float) q.x, (float) q.y);
		}

		for (int j = 0; j < p.getNumInteriorRing(); j++) {
			l = new LengthIndexedLine(p.getInteriorRingN(j));
			for (int i = 0; i < points; i++) {
				Coordinate q = l.extractPoint((i / (float) points) * l.getEndIndex(), offsetDistance);
				outlinePoints[(j + 1) * points + i] = new PVector((float) q.x, (float) q.y);
			}
		}

		return outlinePoints;
	}

	/**
	 * 
	 * @param shape
	 * @param interPointDistance Distance between each point on outline
	 * @param offsetDistance
	 * @return nearest distance such that every distance is equal (maybe be
	 *         different due to rounding)
	 */
	public static PVector[] equidistantOutlineByDistance(PShape shape, float interPointDistance, float offsetDistance) {
		Polygon p = fromPShape(shape);

		LengthIndexedLine l = new LengthIndexedLine(p.getExteriorRing());
		if (interPointDistance > l.getEndIndex()) {
			System.err.println("Interpoint greater than shape length");
			return null;
		}
		int points = (int) Math.round(l.getEndIndex() / interPointDistance);
		ArrayList<PVector> outlinePoints = new ArrayList<>();
		// exterior ring
		for (int i = 0; i < points; i++) {
			Coordinate q = l.extractPoint((i / (float) points) * l.getEndIndex(), offsetDistance);
			outlinePoints.add(new PVector((float) q.x, (float) q.y));
		}

		for (int j = 0; j < p.getNumInteriorRing(); j++) {
			l = new LengthIndexedLine(p.getInteriorRingN(j));
			points = (int) Math.round(l.getEndIndex() / interPointDistance);
			for (int i = 0; i < points; i++) {
				Coordinate q = l.extractPoint((i / (float) points) * l.getEndIndex(), offsetDistance);
				outlinePoints.add(new PVector((float) q.x, (float) q.y));
			}
		}

		final PVector[] out = new PVector[outlinePoints.size()];
		Arrays.setAll(out, outlinePoints::get);
		return out;
	}

	/**
	 * 
	 * @param shape
	 * @param distance 0...1 around shape perimeter; or -1...0 (other direction)
	 * @return
	 */
	public static PVector pointOnOutline(PShape shape, float distance, float offsetDistance) {
		distance %= 1;
		LengthIndexedLine l = new LengthIndexedLine(fromPShape(shape).getExteriorRing());
		Coordinate coord = l.extractPoint(distance * l.getEndIndex(), offsetDistance);
		return new PVector((float) coord.x, (float) coord.y);
	}

	public static PVector getCentroid(PShape shape) {
		Point point = fromPShape(shape).getCentroid();
		return new PVector((float) point.getX(), (float) point.getY());
	}

	/**
	 * Returns a hole-less version of the shape, or boundary of group of shapes
	 * 
	 * @param shape
	 * @return
	 */
	public static PShape boundary(PShape shape) {
		return toPShape(fromPShape(shape).getBoundary());
	}

	/**
	 * TODO return list of points when shape is group
	 * 
	 * @param shape
	 * @param point
	 * @return
	 */
	public static PVector closestVertexToPoint(PShape shape, PVector point) {
		Geometry g = fromPShape(shape);
		Coordinate coord = DistanceOp.nearestPoints(g, pointFromPVector(point))[0];
		return new PVector((float) coord.x, (float) coord.y);
	}

	public static boolean contains(PShape outer, PShape inner) {
		return fromPShape(outer).contains(fromPShape(inner));
	}

	public static boolean containsPoint(PShape shape, PVector point) {
		return fromPShape(shape).contains(pointFromPVector(point));
	}

	public static float distance(PShape a, PShape b) {
		return (float) fromPShape(a).distance(fromPShape(b));
	}

	public static float area(PShape shape) {
		return (float) fromPShape(shape).getArea();
	}

	public static PVector[] vertices(PShape shape) {
		Coordinate[] coords = fromPShape(shape).getCoordinates();
		PVector[] vertices = new PVector[coords.length];
		for (int i = 0; i < coords.length; i++) {
			Coordinate coord = coords[i];
			vertices[i] = new PVector((float) coord.x, (float) coord.y);
		}
		return vertices;
	}

	/**
	 * aka envelope
	 * 
	 * @param shape
	 * @return X,Y,W,H
	 */
	public static float[] bound(PShape shape) {
		Envelope e = (Envelope) fromPShape(shape).getEnvelopeInternal();
		return new float[] { (float) e.getMinX(), (float) e.getMinY(), (float) e.getWidth(), (float) e.getHeight() };
	}

	/**
	 * aka envelope
	 * 
	 * @param shape
	 * @return X1, Y1, X2, Y2
	 */
	public static float[] boundCoords(PShape shape) {
		Envelope e = (Envelope) fromPShape(shape).getEnvelopeInternal();
		return new float[] { (float) e.getMinX(), (float) e.getMinY(), (float) e.getMaxX(), (float) e.getMaxY() };
	}

	public static PShape createSquircle(float x, float y, float width, float height) {
		GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
		shapeFactory.setNumPoints(40);
		shapeFactory.setCentre(new Coordinate(x, y));
//		shapeFactory.setBase(new Coordinate(x, y));
//		shapeFactory.setRotation(1);
		shapeFactory.setWidth(width);
		shapeFactory.setHeight(height);
		return toPShape(shapeFactory.createSquircle());
	}

	/**
	 * https://stackoverflow.com/questions/64252638/how-to-split-a-jts-polygon Split
	 * a polygon into 4 equal quadrants
	 * 
	 * @param p
	 * @return
	 */
	public static ArrayList<PShape> split(PShape shape) {
		Polygon p = fromPShape(shape);
		ArrayList<PShape> ret = new ArrayList<>();

		final Envelope envelope = p.getEnvelopeInternal();
		double minX = envelope.getMinX();
		double maxX = envelope.getMaxX();
		double midX = minX + (maxX - minX) / 2.0;
		double minY = envelope.getMinY();
		double maxY = envelope.getMaxY();
		double midY = minY + (maxY - minY) / 2.0;

		Envelope llEnv = new Envelope(minX, midX, minY, midY);
		Envelope lrEnv = new Envelope(midX, maxX, minY, midY);
		Envelope ulEnv = new Envelope(minX, midX, midY, maxY);
		Envelope urEnv = new Envelope(midX, maxX, midY, maxY);
		Geometry ll = JTS.toGeometry(llEnv).intersection(p);
		Geometry lr = JTS.toGeometry(lrEnv).intersection(p);
		Geometry ul = JTS.toGeometry(ulEnv).intersection(p);
		Geometry ur = JTS.toGeometry(urEnv).intersection(p);
		ret.add(toPShape(ll));
		ret.add(toPShape(lr));
		ret.add(toPShape(ul));
		ret.add(toPShape(ur));

		return ret;
	}

	public static int[] getContourGroups(int[] vertexCodes) {

		int group = 0;

		ArrayList<Integer> groups = new ArrayList<>(vertexCodes.length * 2);

		for (int vertexCode : vertexCodes) {
			switch (vertexCode) {
				case VERTEX:
					groups.add(group);
					break;

				case QUADRATIC_VERTEX:
					groups.add(group);
					groups.add(group);
					break;

				case BEZIER_VERTEX:
					groups.add(group);
					groups.add(group);
					groups.add(group);
					break;

				case CURVE_VERTEX:
					groups.add(group);
					break;

				case BREAK:
					// Marks beginning/end of new contour, and should be proceeded by a VERTEX
					group++;
					break;
			}
		}

		final int[] vertexGroups = new int[groups.size()];
		Arrays.setAll(vertexGroups, groups::get);
		return vertexGroups;
	}

	/**
	 * Basically getVertexCodes, but returns the vertex type for every vertex
	 * 
	 * @param shape
	 * @return
	 */
	private static int[] getVertexTypes(PShape shape) {

		ArrayList<Integer> codes = new ArrayList<>(shape.getVertexCodeCount());

		for (int i = 0; i < shape.getVertexCodeCount(); i++) {
			int vertexCode = shape.getVertexCode(i);
			switch (vertexCode) {
				case VERTEX:
					codes.add(VERTEX);
					break;

				case QUADRATIC_VERTEX:
					codes.add(QUADRATIC_VERTEX);
					codes.add(QUADRATIC_VERTEX);
					break;

				case BEZIER_VERTEX:
					codes.add(BEZIER_VERTEX);
					codes.add(BEZIER_VERTEX);
					codes.add(BEZIER_VERTEX);
					break;

				case CURVE_VERTEX:
					codes.add(CURVE_VERTEX);
					break;

				case BREAK:
			}
		}

		final int[] vertexGroups = new int[codes.size()];
		Arrays.setAll(vertexGroups, codes::get);
		return vertexGroups;
	}

	/**
	 * Subdivide/interpolate/discretise along a quadratic bezier curve, given by its
	 * start, end and control points
	 * 
	 * @param resolution points per spline
	 * @return list of points along curve
	 */
	private static ArrayList<Coordinate> getQuadraticBezierPoints(PVector start, PVector controlPoint, PVector end,
			int resolution) {

		ArrayList<Coordinate> coords = new ArrayList<>();

		for (int j = 0; j < resolution; j++) {
			PVector bezierPoint = getQuadraticBezierCoordinate(start, controlPoint, end, j / 20f);
			coords.add(new Coordinate(bezierPoint.x, bezierPoint.y));
		}
		return coords;
	}

	/**
	 * 
	 * @param start
	 * @param controlPoint
	 * @param end
	 * @param t            0...1
	 * @return
	 */
	private static PVector getQuadraticBezierCoordinate(PVector start, PVector controlPoint, PVector end, float t) {
		float x = (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * controlPoint.x + t * t * end.x;
		float y = (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * controlPoint.y + t * t * end.y;
		return new PVector(x, y);
	}

	private static ArrayList<Coordinate> getCubicBezierPoints(PVector start, PVector controlPoint1,
			PVector controlPoint2, PVector end, int resolution) {

		ArrayList<Coordinate> coords = new ArrayList<>();

		for (int j = 0; j < resolution; j++) {
			PVector bezierPoint = getCubicBezierCoordinate(start, controlPoint1, controlPoint2, end, j / 20f);
			coords.add(new Coordinate(bezierPoint.x, bezierPoint.y));
		}
		return coords;
	}

	private static PVector getCubicBezierCoordinate(PVector start, PVector controlPoint1, PVector controlPoint2,
			PVector end, float t) {
		final float t1 = 1.0f - t;
		float x = start.x * t1 * t1 * t1 + 3 * controlPoint1.x * t * t1 * t1 + 3 * controlPoint2.x * t * t * t1
				+ end.x * t * t * t;
		float y = start.y * t1 * t1 * t1 + 3 * controlPoint1.y * t * t1 * t1 + 3 * controlPoint2.y * t * t * t1
				+ end.y * t * t * t;
		return new PVector(x, y);
	}

	private static GeometryFactory geometryFactory = new GeometryFactory();

	private static Point pointFromPVector(PVector p) {
		return geometryFactory.createPoint(new Coordinate(p.x, p.y));
	}

}
