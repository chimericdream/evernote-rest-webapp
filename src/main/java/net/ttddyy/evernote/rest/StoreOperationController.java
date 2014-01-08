package net.ttddyy.evernote.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.social.evernote.api.Evernote;
import org.springframework.social.evernote.api.StoreClientHolder;
import org.springframework.social.evernote.api.StoreOperations;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Tadaya Tsuyukubo
 */
@RestController
@RequestMapping("/{storeName:noteStore|userStore}")
public class StoreOperationController {

	@Autowired
	private Evernote evernote;

	@RequestMapping(value = "/{methodName}", method = RequestMethod.POST)
	public Object invoke(@PathVariable String storeName, @PathVariable String methodName, @RequestBody JsonNode jsonNode) {

		final StoreOperations storeOperations = getStoreOperations(storeName);
		final Class<?> storeOperationsClass = storeOperations.getClass();

		// In ~StoreClient class, method names are currently unique. passing null to paramTypes arg means find method by name.
		final Method method = ReflectionUtils.findMethod(storeOperationsClass, methodName, null);
		if (method == null) {
			final String message = String.format("Cannot find methodName=[%s] on [%s].", methodName, storeOperationsClass);
			throw new EvernoteRestException(message);
		}

		final Object[] params = resolveParameters(storeOperations, method, jsonNode);
		ReflectionUtils.makeAccessible(method);  // TODO: need this? since it uses interface, all methods are public...
		return ReflectionUtils.invokeMethod(method, storeOperations, params);
	}

	private StoreOperations getStoreOperations(String storeName) {
		if ("noteStore".equals(storeName)) {
			return evernote.noteStoreOperations();
		} else {
			return evernote.userStoreOperations();
		}
	}

	/**
	 * Based on received json, deserialize parameters.
	 */
	private Object[] resolveParameters(StoreOperations storeOperations, Method method, JsonNode jsonNode) {

		final String methodName = method.getName();
		final String[] parameterNames = resolveParameterNames(storeOperations, methodName);
		if (parameterNames == null) {
			final String message = String.format("Cannot find parameter names for method=[%s].", methodName);
			throw new EvernoteRestException(message);
		}

		final Method actualMethod = resolveActualMethod(storeOperations, method);
		return resolveParameterValues(actualMethod, jsonNode);
	}

	private Method resolveActualMethod(StoreOperations storeOperations, Method method) {
		// Cannot retrieve parameter names from interface, even though classes are compiled with debugging information.
		// Since ~StoreClient class which is an underlying implementation class of ~StoreOperations uses same parameter
		// names. So, use parameter names from ~StoreClient impl class for now.
		// Java8 with StandardReflectionParameterNameDiscoverer class, it may be possible to retrieve param names from
		// interface. (haven't checked)
		final String methodName = method.getName();
		final Class<?> storeClientClass = ((StoreClientHolder) storeOperations).getStoreClient().getClass();
		final Method actualMethod = ReflectionUtils.findMethod(storeClientClass, methodName, null);  // find by name
		return actualMethod;
	}

	private String[] resolveParameterNames(StoreOperations storeOperations, String methodName) {
		// Cannot retrieve parameter names from interface, even though classes are compiled with debugging information.
		// Since ~StoreClient class which is an underlying implementation class of ~StoreOperations uses same parameter
		// names. So, use parameter names from ~StoreClient impl class for now.
		// Java8 with StandardReflectionParameterNameDiscoverer class, it may be possible to retrieve param names from
		// interface. (haven't checked)
		final Class<?> storeClientClass = ((StoreClientHolder) storeOperations).getStoreClient().getClass();
		final Method method = ReflectionUtils.findMethod(storeClientClass, methodName, null);  // find by name

		final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
		return discoverer.getParameterNames(method);
	}

	private String[] resolveParameterNames(Method actualMethod) {
		final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
		return discoverer.getParameterNames(actualMethod);
	}


	private Object[] resolveParameterValues(Method actualMethod, JsonNode jsonNode) {
		final ObjectMapper objectMapper = new ObjectMapper();

		final String[] parameterNames = resolveParameterNames(actualMethod);
		final JavaType[] javaTypes = resolveJavaTypes(actualMethod, objectMapper);

		// populate params
		final int parameterSize = actualMethod.getParameterTypes().length;
		final Object[] params = new Object[parameterSize];

		for (int i = 0; i < parameterSize; i++) {
			final JavaType javaType = javaTypes[i];
			final String parameterName = parameterNames[i];

			if (jsonNode.has(parameterName)) {
				final String subJson = jsonNode.get(parameterName).toString();
				try {
					final Object param = objectMapper.readValue(subJson, javaType);
					params[i] = param;
				} catch (IOException e) {
					final String message =
							String.format("Cannot parse part of the json for parameter=[%s]. json=[%s]", parameterName, subJson);
					throw new EvernoteRestException(message, e);
				}
			} else {
				params[i] = null;  // if not included in json, then set as null  TODO: resolve default value??
			}
		}
		return params;
	}

	private JavaType[] resolveJavaTypes(Method actualMethod, ObjectMapper objectMapper) {
		final Class<?>[] parameterTypes = actualMethod.getParameterTypes();
		final List<JavaType> javaTypes = new ArrayList<JavaType>(parameterTypes.length);
		for (int i = 0; i < parameterTypes.length; i++) {
			final Class<?> parameterType = parameterTypes[i];
			final boolean isList = parameterType.isAssignableFrom(List.class);
			final boolean isSet = parameterType.isAssignableFrom(Set.class);
			final boolean isMap = parameterType.isAssignableFrom(Map.class);

			final JavaType type;
			if (isList || isSet) {
				// resolve generic type
				final ResolvableType resolvableType = ResolvableType.forMethodParameter(actualMethod, i);
				final Class<?> genericClass = resolvableType.getGeneric(0).resolve();
				if (genericClass == null) {
					// if couldn't resolve generic type, fallback to parameter type
					type = objectMapper.constructType(parameterType);
				} else {
					if (isList) {
						type = objectMapper.getTypeFactory().constructCollectionType(List.class, genericClass);
					} else {
						type = objectMapper.getTypeFactory().constructCollectionType(Set.class, genericClass);
					}
				}
			} else {
				type = objectMapper.constructType(parameterType);
			}

			javaTypes.add(type);
		}
		return javaTypes.toArray(new JavaType[javaTypes.size()]);
	}

	private Object[] resolveParameterValues(Class<?>[] parameterTypes, String[] parameterNames, JsonNode jsonNode) {
		final ObjectMapper objectMapper = new ObjectMapper();

		// populate params
		final int parameterSize = parameterTypes.length;
		final Object[] params = new Object[parameterSize];

		for (int i = 0; i < parameterSize; i++) {
			final Class<?> parameterType = parameterTypes[i];
			final String parameterName = parameterNames[i];

			if (jsonNode.has(parameterName)) {
				final String subJson = jsonNode.get(parameterName).toString();
				try {
					final Object param = objectMapper.readValue(subJson, parameterType);
					params[i] = param;
				} catch (IOException e) {
					final String message =
							String.format("Cannot parse part of the json for parameter=[%s]. json=[%s]", parameterName, subJson);
					throw new EvernoteRestException(message, e);
				}
			} else {
				params[i] = null;  // if not included in json, then set as null  TODO: resolve default value??
			}
		}
		return params;
	}


}
