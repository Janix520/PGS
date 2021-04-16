package micycle.pgs;

import static micycle.pgs.PGS.GEOM_FACTORY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.util.GeometricShapeFactory;

import processing.core.PConstants;
import processing.core.PShape;
import processing.core.PVector;

/**
 * Conversion between Processing PShapes and JTS Geometries.
 * <p>
 * Methods in this class are used by the library but are kept accessible for
 * more advanced user use cases.
 * 
 * @author Michael Carleton
 *
 */
public class PGS_Conversion implements PConstants {

	private PGS_Conversion() {
	}

	/**
		 * Converts a JTS Geometry to an equivalent PShape. MultiGeometries (collections
		 * of geometries) become GROUP PShapes with children shapes.
		 * 
		 * @param g
		 * @param source PShape to copy fill/stroke details from
		 * @return
		 */
		public static PShape toPShape(final Geometry g) { // , final PShape source
			// TODO use source fill, stroke etc, when creating new PShape
	
			if (g == null) {
				return new PShape(PShape.GEOMETRY);
			}
	
			PShape shape = new PShape();
	
			if (!(shape.getFamily() == GROUP || shape.getFamily() == PShape.PRIMITIVE) && shape.getVertexCount() > 0) {
	//			shape.setStrokeWeight(source.getStrokeWeight(0));
	//			shape.setStroke(source.stroke);
	//			shape.setFill(source.getFill(0));
			} else {
			}
			shape.setFill(true);
			shape.setFill(micycle.pgs.color.RGB.WHITE);
			shape.setStroke(true);
			shape.setStroke(micycle.pgs.color.RGB.PINK);
			shape.setStrokeWeight(4);
	
			switch (g.getGeometryType()) {
				case Geometry.TYPENAME_GEOMETRYCOLLECTION:
				case Geometry.TYPENAME_MULTIPOLYGON:
				case Geometry.TYPENAME_MULTILINESTRING:
					shape.setFamily(GROUP);
					for (int i = 0; i < g.getNumGeometries(); i++) {
						shape.addChild(toPShape(g.getGeometryN(i)));
					}
					break;
	
				case Geometry.TYPENAME_LINEARRING: // closed
				case Geometry.TYPENAME_LINESTRING:
					final LineString l = (LineString) g;
					final boolean closed = l.isClosed();
					shape.setFamily(PShape.PATH);
					shape.beginShape();
					Coordinate[] coords = l.getCoordinates();
					for (int i = 0; i < coords.length - (closed ? 1 : 0); i++) {
						shape.vertex((float) coords[i].x, (float) coords[i].y);
					}
					if (closed) { // closed vertex was skipped, so close the path
						shape.endShape(CLOSE);
					} else {
						shape.endShape();
					}
	
					break;
	
				case Geometry.TYPENAME_POLYGON:
					final Polygon polygon = (Polygon) g;
					shape.setFamily(PShape.PATH);
					shape.beginShape();
	
					/**
					 * Outer and inner loops are iterated up to length-1 to skip the point that
					 * closes the JTS shape (same as the first point).
					 */
					coords = polygon.getExteriorRing().getCoordinates();
					for (int i = 0; i < coords.length - 1; i++) {
						Coordinate coord = coords[i];
						shape.vertex((float) coord.x, (float) coord.y);
					}
	
					for (int j = 0; j < polygon.getNumInteriorRing(); j++) { // holes
						shape.beginContour();
						coords = polygon.getInteriorRingN(j).getCoordinates();
						for (int i = 0; i < coords.length - 1; i++) {
							Coordinate coord = coords[i];
							shape.vertex((float) coord.x, (float) coord.y);
						}
						shape.endContour();
					}
					shape.endShape(CLOSE);
					break;
				default:
					System.err.println(g.getGeometryType() + " are unsupported.");
					break;
			}
	
			return shape;
		}

	/**
	 * Converts a PShape to an equivalent JTS Geometry.
	 * <p>
	 * PShapes with bezier curves are sampled at regular intervals (in which case
	 * the resulting geometry will have more vertices than the input)
	 * <p>
	 * For now, a PShape with multiple children is flattened/unioned since most
	 * library methods are not (yet) programmed to handle multi/disjoint geometries.
	 * 
	 * @param shape
	 * @return a JTS Polygon or MultiPolygon
	 */
	public static Geometry fromPShape(PShape shape) {

		Geometry g = null;

		switch (shape.getFamily()) {
			case PShape.GROUP:
				final ArrayList<PShape> flatChildren = new ArrayList<PShape>();
				getChildren(shape, flatChildren);
				flatChildren.removeIf(s -> s.getFamily() == PShape.GROUP);
				Polygon[] children = new Polygon[flatChildren.size()];
				for (int i = 0; i < children.length; i++) {
					children[i] = (Polygon) fromPShape(flatChildren.get(i));
				}
				// TODO for now, buffer/flatten polygons so that methods handle them properly
				return (GEOM_FACTORY.createMultiPolygon(children).buffer(0));
			case PShape.GEOMETRY:
			case PShape.PATH:
				g = fromVertices(shape);
				break;
			case PShape.PRIMITIVE:
				g = fromPrimitive(shape);
				break;
		}

		return g;
	}

	/**
	 * Creates a JTS Polygon from a geometry or path PShape.
	 */
	private static Polygon fromVertices(PShape shape) {

		if (shape.getVertexCount() < 3) {
			System.err.println("Input PShape has less than 3 vertices (not polygonal).");
			return GEOM_FACTORY.createPolygon();
		}

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
			 * Sample bezier curves at regular intervals to produce smooth Geometry
			 */
			switch (vertexCodes[i]) {
				case QUADRATIC_VERTEX:
					coords.get(lastGroup).addAll(getQuadraticBezierPoints(shape.getVertex(i - 1), shape.getVertex(i),
							shape.getVertex(i + 1), PGS.CURVE_SAMPLES));
					i += 1;
					continue;
				case BEZIER_VERTEX: // aka cubic bezier
					coords.get(lastGroup).addAll(getCubicBezierPoints(shape.getVertex(i - 1), shape.getVertex(i),
							shape.getVertex(i + 1), shape.getVertex(i + 2), PGS.CURVE_SAMPLES));
					i += 2;
					continue;
				default:
					coords.get(lastGroup).add(new Coordinate(shape.getVertexX(i), shape.getVertexY(i)));
					break;
			}
		}

		for (ArrayList<Coordinate> contour : coords) {
			if (!contour.get(0).equals2D(contour.get(contour.size() - 1))) {
				contour.add(contour.get(0)); // points of LinearRing must form a closed linestring
			}
		}

		final Coordinate[] outerCoords = coords.get(0).toArray(new Coordinate[coords.get(0).size()]);

		LinearRing outer = GEOM_FACTORY.createLinearRing(outerCoords); // should always be valid

		/**
		 * Create linear ring for each hole in the shape
		 */
		LinearRing[] holes = new LinearRing[coords.size() - 1];

		for (int j = 1; j < coords.size(); j++) {
			final Coordinate[] innerCoords = coords.get(j).toArray(new Coordinate[coords.get(j).size()]);
			holes[j - 1] = GEOM_FACTORY.createLinearRing(innerCoords);
		}

		return GEOM_FACTORY.createPolygon(outer, holes);
	}

	/**
	 * Creates a JTS Polygon from a primitive PShape. Primitive PShapes are those
	 * where createShape() is used to create them, and can take any of these types:
	 * POINT, LINE, TRIANGLE, QUAD, RECT, ELLIPSE, ARC, BOX, SPHERE. They do not
	 * have direct vertex data.
	 */
	private static Polygon fromPrimitive(PShape shape) {
		final GeometricShapeFactory shapeFactory = new GeometricShapeFactory();
		shapeFactory.setNumPoints(PGS.CURVE_SAMPLES * 4); // TODO magic constant

		switch (shape.getKind()) {
			case ELLIPSE:
				shapeFactory.setCentre(new Coordinate(shape.getParam(0), shape.getParam(1)));
				shapeFactory.setWidth(shape.getParam(2));
				shapeFactory.setHeight(shape.getParam(3));
				return shapeFactory.createEllipse();
			case TRIANGLE:
				Coordinate[] coords = new Coordinate[3 + 1];
				Coordinate c1 = new Coordinate(shape.getParam(0), shape.getParam(1));
				coords[0] = c1;
				coords[1] = new Coordinate(shape.getParam(2), shape.getParam(3));
				coords[2] = new Coordinate(shape.getParam(4), shape.getParam(5));
				coords[3] = c1.copy(); // close loop
				return GEOM_FACTORY.createPolygon(coords);
			case RECT:
				shapeFactory.setCentre(new Coordinate(shape.getParam(0), shape.getParam(1)));
				shapeFactory.setWidth(shape.getParam(2));
				shapeFactory.setHeight(shape.getParam(3));
				return shapeFactory.createRectangle();
			case QUAD:
				coords = new Coordinate[4 + 1];
				c1 = new Coordinate(shape.getParam(0), shape.getParam(1));
				coords[0] = c1;
				coords[1] = new Coordinate(shape.getParam(2), shape.getParam(3));
				coords[2] = new Coordinate(shape.getParam(4), shape.getParam(5));
				coords[3] = new Coordinate(shape.getParam(6), shape.getParam(7));
				coords[4] = c1.copy(); // close loop
				return GEOM_FACTORY.createPolygon(coords);
			case ARC:
				shapeFactory.setCentre(new Coordinate(shape.getParam(0), shape.getParam(1)));
				shapeFactory.setWidth(shape.getParam(2));
				shapeFactory.setHeight(shape.getParam(3));
				return shapeFactory.createArcPolygon(-Math.PI / 2 + shape.getParam(4), shape.getParam(5));
			case LINE:
			case POINT:
				System.err.print("Non-polygon primitives are not supported.");
				break;
			case BOX:
			case SPHERE:
				System.err.print("3D primitives are not supported.");
				break;
			default:
				System.err.print(shape.getKind() + " primitives are not supported.");

		}
		return GEOM_FACTORY.createPolygon(); // empty polygon
	}

	/**
	 * Kinda recursive, caller must provide fresh arraylist. Output includes the
	 * parent-most (input) shape. Output is flattened, does not respect a hierarchy
	 * of parent-child PShapes.
	 * 
	 * @param shape
	 * @param visited
	 * @return
	 */
	public static PShape getChildren(PShape shape, List<PShape> visited) {
		visited.add(shape);

		if (shape.getChildCount() == 0 || shape.getKind() != GROUP) {
			return shape;
		}

		for (PShape child : shape.getChildren()) {
			getChildren(child, visited);
		}
		return null;
	}

	/**
	 * Sets the fill color for the PShape and all of it's children recursively (and
	 * disables stroke).
	 * 
	 * @param shape
	 * @see #setAllStrokeColor(PShape, int, int)
	 */
	public static void setAllFillColor(PShape shape, int color) {
		List<PShape> all = new ArrayList<PShape>();
		getChildren(shape, all);
		all.forEach(child -> {
			child.setStroke(false);
			child.setFill(true);
			child.setFill(color);
		});
	}

	/**
	 * Sets the stroke color for the PShape and all of it's children recursively.
	 * 
	 * @param shape
	 * @see {@link #setAllFillColor(PShape, int)}
	 */
	public static void setAllStrokeColor(PShape shape, int color, int strokeWeight) {
		List<PShape> all = new ArrayList<PShape>();
		getChildren(shape, all);
		all.forEach(child -> {
			child.setStroke(true);
			child.setStroke(color);
			child.setStrokeWeight(strokeWeight);
		});
	}

	public static void disableAllFill(PShape shape) {
		ArrayList<PShape> all = new ArrayList<PShape>();
		getChildren(shape, all);
		all.forEach(child -> {
			child.setFill(false);
		});
	}

	private static int[] getContourGroups(int[] vertexCodes) {

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

		List<Integer> codes = new ArrayList<>(shape.getVertexCodeCount());

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

	// TODO Modify interpolation resolution depending on bezier length (ideally
	// sample per 1 unit)

	/**
	 * Subdivide/interpolate/discretise along a quadratic bezier curve, given by its
	 * start, end and control points
	 * 
	 * @param resolution points per spline
	 * @return list of points along curve
	 */
	private static List<Coordinate> getQuadraticBezierPoints(PVector start, PVector controlPoint, PVector end,
			int resolution) {

		List<Coordinate> coords = new ArrayList<>();

		for (int j = 0; j < resolution; j++) {
			PVector bezierPoint = getQuadraticBezierCoordinate(start, controlPoint, end, j / (float) resolution);
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

	private static List<Coordinate> getCubicBezierPoints(PVector start, PVector controlPoint1, PVector controlPoint2,
			PVector end, int resolution) {

		List<Coordinate> coords = new ArrayList<>();

		for (int j = 0; j < resolution; j++) {
			PVector bezierPoint = getCubicBezierCoordinate(start, controlPoint1, controlPoint2, end,
					j / (float) resolution);
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
}
