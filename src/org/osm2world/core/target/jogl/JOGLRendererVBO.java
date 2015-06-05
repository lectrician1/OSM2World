package org.osm2world.core.target.jogl;

import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static org.osm2world.core.target.common.rendering.OrthoTilesUtil.CardinalDirection.closestCardinal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.target.common.Primitive;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Material.Transparency;
import org.osm2world.core.target.common.rendering.Camera;
import org.osm2world.core.target.common.rendering.Projection;
import org.osm2world.core.target.common.rendering.OrthoTilesUtil.CardinalDirection;

public abstract class JOGLRendererVBO extends JOGLRenderer {

	protected static final boolean DOUBLE_PRECISION_RENDERING = false;

	/** VBOs with static, non-alphablended geometry for each material */
	protected List<VBOData<?>> vbos = new ArrayList<VBOData<?>>();
	
	/** alphablended primitives, need to be sorted by distance from camera */
	protected List<PrimitiveWithMaterial> transparentPrimitives =
			new ArrayList<PrimitiveWithMaterial>();
	
	/**
	 * the camera direction that was the basis for the previous sorting
	 * of {@link #transparentPrimitives}.
	 */
	private CardinalDirection currentPrimitiveSortDirection = null;

	protected static final class PrimitiveWithMaterial {
		
		public final Primitive primitive;
		public final Material material;
		
		private PrimitiveWithMaterial(Primitive primitive, Material material) {
			this.primitive = primitive;
			this.material = material;
		}
		
	}

	/**
	 * returns the number of values for each vertex
	 * in the vertex buffer layout appropriate for a given material.
	 */
	public static int getValuesPerVertex(Material material) {
		
		int numValues = 6; // vertex coordinates and normals
		
		if (material.getTextureDataList() != null) {
			numValues += 2 * material.getTextureDataList().size();
		}
		
		return numValues;
		
	}
	
	JOGLRendererVBO(JOGLTextureManager textureManager) {
		super(textureManager);
	}
	
	protected void init(PrimitiveBuffer primitiveBuffer) {
		
		for (Material material : primitiveBuffer.getMaterials()) {
			
			if (material.getTransparency() == Transparency.TRUE) {
				
				for (Primitive primitive : primitiveBuffer.getPrimitives(material)) {
					transparentPrimitives.add(
							new PrimitiveWithMaterial(primitive, material));
				}
				
			} else {
				
				Collection<Primitive> primitives = primitiveBuffer.getPrimitives(material);
				vbos.add(this.createVBOData(textureManager, material, primitives));
				
			}
			
		}
		
	}
	
	protected void sortPrimitivesBackToFront(final Camera camera,
			final Projection projection) {
		
		if (projection.isOrthographic() &&
				abs(camera.getViewDirection().xz().angle() % (PI/2)) < 0.01 ) {
			
			/* faster sorting for cardinal directions */
			
			CardinalDirection closestCardinal = closestCardinal(camera.getViewDirection().xz().angle());
			
			if (closestCardinal.isOppositeOf(currentPrimitiveSortDirection)) {
				
				Collections.reverse(transparentPrimitives);
				
			} else if (closestCardinal != currentPrimitiveSortDirection) {
				
				Comparator<PrimitiveWithMaterial> comparator = null;
				
				switch(closestCardinal) {
				
				case N:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p2).z, primitivePos(p1).z);
						}
					};
					break;
					
				case E:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p2).x, primitivePos(p1).x);
						}
					};
					break;
					
				case S:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p1).z, primitivePos(p2).z);
						}
					};
					break;
					
				case W:
					comparator = new Comparator<PrimitiveWithMaterial>() {
						@Override
						public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
							return Double.compare(primitivePos(p1).x, primitivePos(p2).x);
						}
					};
					break;
					
				}
				
				Collections.sort(transparentPrimitives, comparator);
				
			}
			
			currentPrimitiveSortDirection = closestCardinal;
			
		} else {
			
			/* sort based on distance to camera */
			
			Collections.sort(transparentPrimitives, new Comparator<PrimitiveWithMaterial>() {
				@Override
				public int compare(PrimitiveWithMaterial p1, PrimitiveWithMaterial p2) {
					return Double.compare(
							distanceToCameraSq(camera, p2),
							distanceToCameraSq(camera, p1));
				}
			});
			
			currentPrimitiveSortDirection = null;
			
		}
		
	}
	
	private double distanceToCameraSq(Camera camera, PrimitiveWithMaterial p) {
		return primitivePos(p).distanceToSquared(camera.getPos());
	}
	
	private VectorXYZ primitivePos(PrimitiveWithMaterial p) {
		
		double sumX = 0, sumY = 0, sumZ = 0;
		
		for (VectorXYZ v : p.primitive.vertices) {
			sumX += v.x;
			sumY += v.y;
			sumZ += v.z;
		}
		
		return new VectorXYZ(sumX / p.primitive.vertices.size(),
				sumY / p.primitive.vertices.size(),
				sumZ / p.primitive.vertices.size());
		
	}
	
	@Override
	public void freeResources() {
		
		if (vbos != null) {
			for (VBOData<?> vbo : vbos) {
				vbo.delete();
			}
			vbos = null;
		}
		
		super.freeResources();
		
	}
	
	abstract VBOData<?> createVBOData(JOGLTextureManager textureManager, Material material, Collection<Primitive> primitives);
}
