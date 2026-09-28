package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.LLMBackend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether the images a request carries can actually be sent, and drops them when they
 * cannot.
 *
 * <h2>Why a request is checked before it goes out</h2>
 *
 * <p>Three things have to be true for an image to reach a model. The wire must have somewhere to
 * put it, which every backend shipped here does and one added later may not. The wire must also
 * take the format the image is in, and the four wires do not agree about that: Gemini takes HEIC
 * and HEIF and does not take GIF, and the other three are the other way round. The model must read
 * images, and that varies inside a single provider -- the same Anthropic or OpenAI account serves
 * models that take a picture and models that return 400 for one.</p>
 *
 * <p>Each answer must be given here rather than by the provider. A backend that cannot carry the
 * image would send the text alone, and the model would answer about a picture it was never shown --
 * a wrong answer with nothing in it to say so. A format the wire does not take, and a model that
 * refuses the image, each fail the whole request, so a question that would have been answered from
 * its text is lost. Dropping the image and saying so leaves the text answered and the reason on
 * screen.</p>
 *
 * <h2>Why an unknown model is allowed to try</h2>
 *
 * <p>The catalogue comes from models.dev and is fetched, cached and occasionally stale. A model it
 * has never heard of is an ordinary case: a private deployment, a gateway alias, a model released
 * after the cache was written. Refusing those would make this the reason a working setup stopped
 * sending images. When the catalogue has no entry the request goes out as it is, and the provider
 * decides.</p>
 */
public final class ImageChannel {

    private static final CadetLogger LOG = CadetLogger.getLogger(ImageChannel.class);

    /**
     * What has already been said, so the same sentence is not printed twice.
     *
     * <h2>Why the files are part of what makes a warning new</h2>
     *
     * <p>A latch on the provider, the model and the reason alone said it once for the life of the
     * process. The second screenshot dropped by the same model, and every one after it, was dropped
     * in silence -- so a user who had been told once, forty turns earlier, went on attaching
     * pictures to a model that was reading none of them and saw nothing to suggest it. The reason
     * repeats; the files do not, and it is the files the sentence is about.</p>
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ImageChannel() {
    }

    /**
     * Returns the request as it can be sent.
     *
     * @param promptData the request, which may carry images
     * @param backend    the wire it is about to go out on
     * @param providerId the connector id, for looking the model up and for the message
     * @param modelId    the model being asked
     * @return {@code promptData} unchanged when every image can be sent, otherwise a copy carrying
     *         only the ones that can
     */
    public static PromptData fit(PromptData promptData,
                                 LLMBackend backend,
                                 String providerId,
                                 String modelId) {
        if (promptData == null || !promptData.hasImages()) {
            return promptData;
        }
        if (backend != null && backend.imageMediaTypes().isEmpty()) {
            warnOnce(providerId, modelId, promptData.getImages(),
                     "this provider's API has no place to put an image");
            return promptData.withImages(List.of());
        }
        if (!modelReadsImages(providerId, modelId)) {
            warnOnce(providerId, modelId, promptData.getImages(),
                     "this model does not read images");
            return promptData.withImages(List.of());
        }
        if (backend == null) {
            return promptData;
        }
        return withoutUnreadableTypes(promptData, backend, providerId, modelId);
    }

    /**
     * Returns the request without the images this wire does not document a place for.
     *
     * <h2>Why a format is dropped rather than sent and refused</h2>
     *
     * <p>The wires do not take the same formats. Gemini takes HEIC and HEIF and does not take GIF;
     * the other three take GIF and neither of the other two. A format a wire does not take is not
     * ignored by the provider, it is refused along with the request it arrived in, so an animation
     * dropped on a Gemini prompt ended the turn with a protocol error and took the question with
     * it. Dropping the image here leaves the question asked and the reason on screen, which is what
     * this class does with every other reason a picture cannot be sent.</p>
     *
     * @param promptData the request, which carries at least one image
     * @param backend    the wire it is about to go out on
     * @param providerId the connector id, for the message
     * @param modelId    the model being asked, for the message
     * @return {@code promptData} unchanged when the wire takes every image, otherwise a copy
     *         carrying only the ones it takes
     */
    private static PromptData withoutUnreadableTypes(PromptData promptData,
                                                     LLMBackend backend,
                                                     String providerId,
                                                     String modelId) {
        Set<String> carried = backend.imageMediaTypes();
        List<PromptImage> keep = new ArrayList<>();
        List<PromptImage> drop = new ArrayList<>();
        for (PromptImage image : promptData.getImages()) {
            (carried.contains(image.mediaType()) ? keep : drop).add(image);
        }
        if (drop.isEmpty()) {
            return promptData;
        }
        warnOnce(providerId, modelId, drop, "this provider's API does not take "
                                            + typeNamesOf(drop));
        return promptData.withImages(List.copyOf(keep));
    }

    /**
     * Whether the catalogue says this model reads images.
     *
     * <p>Asked of the provider the request goes to and then of the vendor the model id names, so
     * that a model reached through a gateway is judged on what it can do rather than treated as
     * unknown. Unknown still means the images are sent: the catalogue is an optimisation here, and
     * the provider is the one that decides.</p>
     *
     * @param providerId the connector id
     * @param modelId    the model
     * @return {@code true} when it does, or when the catalogue does not know the model
     */
    static boolean modelReadsImages(String providerId, String modelId) {
        try {
            ModelsDevModel model = ModelCatalog.getInstance().findServedModel(providerId, modelId);
            return model == null || model.isAttachment();
        } catch (Exception unreadable) {
            // The catalogue is an optimisation here, never a gate: if it cannot be read, the
            // provider decides.
            LOG.debug("Could not read the model catalogue for " + providerId + "/" + modelId
                      + "; sending the images anyway: " + unreadable.getMessage());
            return true;
        }
    }

    /** Says what was dropped and why, once per provider, model, reason and set of files. */
    private static void warnOnce(String providerId, String modelId, List<PromptImage> dropped,
                                 String reason) {
        String names = namesOf(dropped);
        String key   = (providerId + "/" + modelId + ": " + reason + ": " + names)
                .toLowerCase(Locale.ROOT);
        if (!WARNED.add(key)) {
            return;
        }
        LOG.warn("Not sending " + names + " to " + providerId + "/" + modelId + ": " + reason
                 + ". The rest of the request was sent.");
    }

    /**
     * @param dropped the images being dropped
     * @return the file names, comma-separated, in the order they were attached
     */
    private static String namesOf(List<PromptImage> dropped) {
        StringBuilder names = new StringBuilder();
        for (PromptImage image : dropped) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(image.name());
        }
        return names.toString();
    }

    /**
     * @param dropped the images being dropped
     * @return the formats they are in, comma-separated, each named once
     */
    private static String typeNamesOf(List<PromptImage> dropped) {
        StringBuilder types = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (PromptImage image : dropped) {
            if (!seen.add(image.mediaType())) {
                continue;
            }
            if (types.length() > 0) {
                types.append(", ");
            }
            types.append(image.mediaType());
        }
        return types.toString();
    }

    /** Clears the latch. Intended for tests. */
    static void resetForTesting() {
        WARNED.clear();
    }
}
