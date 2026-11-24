import CoreLocation
import Foundation

/** Dove sei, per le fermate vicine. */
@MainActor
final class Posizione: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var lat: Double?
    @Published private(set) var lon: Double?
    @Published private(set) var negata = false

    private let gestore = CLLocationManager()

    override init() {
        super.init()
        if let (lat, lon) = Prova.posizione {
            self.lat = lat
            self.lon = lon
            return
        }
        gestore.delegate = self
        gestore.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        gestore.distanceFilter = 25
    }

    func avvia() {
        guard Prova.posizione == nil else { return }
        switch gestore.authorizationStatus {
        case .notDetermined: gestore.requestWhenInUseAuthorization()
        case .denied, .restricted: negata = true
        default: gestore.startUpdatingLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let stato = manager.authorizationStatus
        Task { @MainActor in
            self.negata = stato == .denied || stato == .restricted
            if stato == .authorizedWhenInUse || stato == .authorizedAlways { self.gestore.startUpdatingLocation() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let ultima = locations.last else { return }
        Task { @MainActor in
            self.lat = ultima.coordinate.latitude
            self.lon = ultima.coordinate.longitude
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
