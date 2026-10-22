$version: "2"

namespace example.weather

use aws.protocols#restJson1

/// Provides weather forecasts for cities around the world.
///
/// Look up a city with <code>GetCity</code>, list the cities the service knows about, and read the
/// forecast for a city. This model is based on the Smithy quick start.
@restJson1
@title("Weather Service")
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "pageSize")
service Weather {
    version: "2006-03-01"
    resources: [City]
    operations: [GetCurrentTime]
}

resource City {
    identifiers: { cityId: CityId }
    properties: { coordinates: CityCoordinates }
    read: GetCity
    list: ListCities
    resources: [Forecast]
    operations: [GetCityImage]
}

resource Forecast {
    identifiers: { cityId: CityId }
    properties: { chanceOfRain: Float }
    read: GetForecast
}

// "pattern" is a trait.
@pattern("^[A-Za-z0-9 ]+$")
string CityId

/// Returns the name and coordinates of a city.
@readonly
@http(method: "GET", uri: "/cities/{cityId}")
operation GetCity {
    input := for City {
        // "cityId" provides the identifier for the resource and
        // has to be marked as required.
        @required
        @httpLabel
        $cityId
    }

    output := for City {
        // "required" is used on output to indicate if the service
        // will always provide a value for the member.
        // "notProperty" indicates that top-level input member "name"
        // is not bound to any resource property.
        @required
        @notProperty
        name: String

        @required
        $coordinates
    }

    errors: [NoSuchResource]
}

// This structure is nested within GetCityOutput.
structure CityCoordinates {
    /// Degrees east or west of the prime meridian.
    @required
    longitude: Float

    /// Degrees north or south of the equator.
    @required
    latitude: Float
}

// "error" is a trait that is used to specialize
// a structure as an error.
@error("client")
@httpError(404)
structure NoSuchResource {
    @required
    resourceType: String
}

// The paginated trait indicates that the operation may
// return truncated results.
/// Lists the cities the service has forecasts for, one page at a time.
@readonly
@paginated(items: "items")
@http(method: "GET", uri: "/cities")
operation ListCities {
    input := {
        @httpQuery("nextToken")
        nextToken: String

        /// The largest number of cities to return in one page.
        @httpQuery("maxResults")
        @range(min: 1, max: 100)
        pageSize: Integer
    }

    output := {
        nextToken: String

        @required
        items: CitySummaries
    }
}

// CitySummaries is a list of CitySummary structures.
list CitySummaries {
    member: CitySummary
}

// CitySummary contains a reference to a City.
@references([
    {
        resource: City
    }
])
structure CitySummary {
    @required
    cityId: CityId

    @required
    name: String
}

@readonly
@http(method: "GET", uri: "/currentTime")
operation GetCurrentTime {
    output := {
        @required
        time: Timestamp
    }
}

/// Returns the forecast for a city.
@readonly
@http(method: "GET", uri: "/cities/{cityId}/forecast")
operation GetForecast {
    input := for Forecast {
        // "cityId" provides the only identifier for the resource since
        // a Forecast doesn't have its own.
        @required
        @httpLabel
        $cityId
    }

    output := for Forecast {
        $chanceOfRain
    }
}

/// Returns an image of a city.
@readonly
@http(method: "GET", uri: "/cities/{cityId}/image")
operation GetCityImage {
    input := for City {
        @required
        @httpLabel
        $cityId

        /// The kind of image to return.
        @httpQuery("type")
        @notProperty
        imageType: ImageType = "PNG"
    }

    output := {
        /// The image bytes, streamed to the client.
        @required
        @httpPayload
        @notProperty
        image: CityImageData

        @httpHeader("Content-Type")
        @notProperty
        contentType: String
    }

    errors: [NoSuchResource]
}

/// The image formats the service can return.
enum ImageType {
    PNG
    JPEG
}

@streaming
blob CityImageData
