package denominator.route53;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Provides;
import denominator.AllProfileResourceRecordSetApi;
import denominator.BasicProvider;
import denominator.CheckConnection;
import denominator.DNSApiManager;
import denominator.ResourceRecordSetApi;
import denominator.ZoneApi;
import denominator.config.GeoUnsupported;
import denominator.config.NothingToClose;
import denominator.profile.WeightedResourceRecordSetApi;
import denominator.route53.Route53ErrorDecoder.Messages;
import denominator.route53.Route53ErrorDecoder.Route53Error;
import feign.Feign;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.sax.SAXDecoder;

public class Route53Provider extends BasicProvider {
    private final String url;

    public Route53Provider() {
        this(null);
    }

    /**
     * @param url
     *            if empty or null use default
     */
    public Route53Provider(String url) {
        this.url = url == null || url.isEmpty() ? "https://route53.amazonaws.com" : url;
    }

    @Override
    public String url() {
        return url;
    }

    // http://docs.aws.amazon.com/Route53/latest/APIReference/API_ChangeResourceRecordSets.html
    @Override
    public Set<String> basicRecordTypes() {
        Set<String> types = new LinkedHashSet<String>();
        types.addAll(Arrays.asList("A", "AAAA", "CNAME", "MX", "NS", "PTR", "SOA", "SPF", "SRV", "TXT"));
        return types;
    }

    // http://docs.aws.amazon.com/Route53/latest/APIReference/API_ChangeResourceRecordSets.html
    @Override
    public Map<String, Collection<String>> profileToRecordTypes() {
        Map<String, Collection<String>> profileToRecordTypes = new LinkedHashMap<String, Collection<String>>();
        profileToRecordTypes.put("weighted", Arrays.asList("A", "AAAA", "CNAME", "MX", "PTR", "SPF", "SRV", "TXT"));
        profileToRecordTypes.put("roundRobin", Arrays.asList("A", "AAAA", "MX", "NS", "PTR", "SPF", "SRV", "TXT"));
        return profileToRecordTypes;
    }

    @Override
    public boolean supportsDuplicateZoneNames() {
        return true;
    }

    @Override
    public Map<String, Collection<String>> credentialTypeToParameterNames() {
        Map<String, Collection<String>> options = new LinkedHashMap<String, Collection<String>>();
        options.put("accessKey", Arrays.asList("accessKey", "secretKey"));
        options.put("session", Arrays.asList("accessKey", "secretKey", "sessionToken"));
        return options;
    }

    @dagger.Module(injects = DNSApiManager.class, complete = false, includes = { NothingToClose.class,
            GeoUnsupported.class, InstanceProfileCredentialsProvider.class, FeignModule.class })
    public static final class Module {

        @Provides
        CheckConnection checkConnection(HostedZonesReadable checkConnection) {
            return checkConnection;
        }

        @Provides
        @Singleton
        ZoneApi provideZoneApi(Route53 api) {
            return new Route53ZoneApi(api);
        }

        @Provides
        @Singleton
        ResourceRecordSetApi.Factory provideResourceRecordSetApiFactory(
                Route53AllProfileResourceRecordSetApi.Factory roApi, Route53 api) {
            return new Route53ResourceRecordSetApi.Factory(roApi, api);
        }

        @Provides
        @Singleton
        AllProfileResourceRecordSetApi.Factory provideAllProfileResourceRecordSetApiFactory(Route53 api) {
            return new Route53AllProfileResourceRecordSetApi.Factory(api);
        }

        @Provides
        WeightedResourceRecordSetApi.Factory provideWeightedResourceRecordSetApiFactory(
                Route53WeightedResourceRecordSetApi.Factory in) {
            return in;
        }

        /**
         * See <a href=
         * "http://docs.aws.amazon.com/Route53/latest/APIReference/API_ChangeResourceRecordSets.html"
         * >valid weights</a>
         */
        @Provides
        @Singleton
        @Named("weighted")
        SortedSet<Integer> provideSupportedWeights() {
            SortedSet<Integer> supportedWeights = new TreeSet<Integer>();
            for (int i = 0; i <= 255; i++)
                supportedWeights.add(i);
            return Collections.unmodifiableSortedSet(supportedWeights);
        }
    }

    @dagger.Module(//
    injects = Route53ResourceRecordSetApi.Factory.class, //
    complete = false, // doesn't bind Provider used by Route53Target
    includes = { Feign.Defaults.class, XMLCodec.class })
    public static final class FeignModule {

        @Provides
        @Singleton
        Route53 route53(Feign feign, Route53Target target) {
            return feign.newInstance(target);
        }

        @Provides
        Map<String, String> authHeaders(InvalidatableAuthenticationHeadersProvider provider) {
            return provider.get();
        }
    }

    @dagger.Module(//
    injects = { Encoder.class, Decoder.class, ErrorDecoder.class },//
    overrides = true, // ErrorDecoder
    addsTo = Feign.Defaults.class //
    )
    static final class XMLCodec {

        @Provides
        Encoder encodeChanges(EncodeChanges encoder) {
            return encoder;
        }

        @Provides
        Decoder saxDecoder() {
            return SAXDecoder.builder()//
                    .registerContentHandler(ListHostedZonesResponseHandler.class)//
                    .registerContentHandler(ListResourceRecordSetsResponseHandler.class)//
                    .registerContentHandler(Messages.class)//
                    .registerContentHandler(Route53Error.class)//
                    .build();
        }

        @Provides
        ErrorDecoder errorDecoders(Route53ErrorDecoder errorDecoder) {
            return errorDecoder;
        }
    }
}
