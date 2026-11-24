package com.urbancompany.platform.common;

import java.util.ArrayList;
import java.util.List;

public final class GeoUtils {
    private static final double R = 6371.0088;
    private GeoUtils() {}

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /** Parses "lat,lng;lat,lng;..." into [lat,lng] pairs. */
    public static List<double[]> parsePolygon(String coords) {
        List<double[]> pts = new ArrayList<>();
        if (coords == null || coords.isBlank()) return pts;
        for (String p : coords.split(";")) {
            String[] ll = p.trim().split(",");
            pts.add(new double[]{Double.parseDouble(ll[0].trim()), Double.parseDouble(ll[1].trim())});
        }
        return pts;
    }

    /** Ray-casting point-in-polygon test; polygon vertices are [lat,lng]. */
    public static boolean inPolygon(double lat, double lng, List<double[]> poly) {
        if (poly.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double yi = poly.get(i)[0], xi = poly.get(i)[1], yj = poly.get(j)[0], xj = poly.get(j)[1];
            boolean intersect = ((yi > lat) != (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}
