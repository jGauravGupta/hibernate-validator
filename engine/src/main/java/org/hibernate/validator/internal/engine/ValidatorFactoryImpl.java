/*
 * Hibernate Validator, declare and validate application constraints
 *
 * License: Apache License, Version 2.0
 * See the license.txt file in the root directory or <http://www.apache.org/licenses/LICENSE-2.0>.
 */
package org.hibernate.validator.internal.engine;

import static org.hibernate.validator.internal.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashSet;

import java.lang.annotation.Annotation;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.validation.ClockProvider;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.ParameterNameProvider;
import javax.validation.TraversableResolver;
import javax.validation.Validator;
import javax.validation.spi.ConfigurationState;
import javax.validation.valueextraction.ValueExtractor;

import org.hibernate.validator.HibernateValidatorConfiguration;
import org.hibernate.validator.HibernateValidatorContext;
import org.hibernate.validator.HibernateValidatorFactory;
import org.hibernate.validator.cfg.ConstraintMapping;
import org.hibernate.validator.internal.cfg.context.DefaultConstraintMapping;
import org.hibernate.validator.internal.engine.cascading.ValueExtractorManager;
import org.hibernate.validator.internal.engine.constraintdefinition.ConstraintDefinitionContribution;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager;
import org.hibernate.validator.internal.metadata.BeanMetaDataManager;
import org.hibernate.validator.internal.metadata.core.ConstraintHelper;
import org.hibernate.validator.internal.metadata.provider.MetaDataProvider;
import org.hibernate.validator.internal.metadata.provider.ProgrammaticMetaDataProvider;
import org.hibernate.validator.internal.metadata.provider.XmlMetaDataProvider;
import org.hibernate.validator.internal.util.ExecutableHelper;
import org.hibernate.validator.internal.util.ExecutableParameterNameProvider;
import org.hibernate.validator.internal.util.StringHelper;
import org.hibernate.validator.internal.util.TypeResolutionHelper;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;
import org.hibernate.validator.internal.util.privilegedactions.LoadClass;
import org.hibernate.validator.internal.util.privilegedactions.NewInstance;
import org.hibernate.validator.spi.cfg.ConstraintMappingContributor;

/**
 * Factory returning initialized {@code Validator} instances. This is the Hibernate Validator default
 * implementation of the {@code ValidatorFactory} interface.
 *
 * @author Emmanuel Bernard
 * @author Hardy Ferentschik
 * @author Gunnar Morling
 * @author Kevin Pollet &lt;kevin.pollet@serli.com&gt; (C) 2011 SERLI
 * @author Chris Beckey &lt;cbeckey@paypal.com&gt;
 */
public class ValidatorFactoryImpl implements HibernateValidatorFactory {

	private static final Log log = LoggerFactory.make();

	/**
	 * The default message interpolator for this factory.
	 */
	private final MessageInterpolator messageInterpolator;

	/**
	 * The default traversable resolver for this factory.
	 */
	private final TraversableResolver traversableResolver;

	/**
	 * The default parameter name provider for this factory.
	 */
	private final ExecutableParameterNameProvider parameterNameProvider;

	/**
	 * Provider for the current time when validating {@code @Future} or {@code @Past}
	 */
	private final ClockProvider clockProvider;

	/**
	 * The default constraint validator factory for this factory.
	 */
	private final ConstraintValidatorManager constraintValidatorManager;

	/**
	 * Programmatic constraints passed via the Hibernate Validator specific API. Empty if there are
	 * no programmatic constraints
	 */
	private final Set<DefaultConstraintMapping> constraintMappings;

	/**
	 * Helper for dealing with built-in validators and determining custom constraint annotations.
	 */
	private final ConstraintHelper constraintHelper;

	/**
	 * Used for resolving type parameters. Thread-safe.
	 */
	private final TypeResolutionHelper typeResolutionHelper;

	/**
	 * Used for discovering overridden methods. Thread-safe.
	 */
	private final ExecutableHelper executableHelper;

	/**
	 * Hibernate Validator specific flag to abort validation on first constraint violation.
	 */
	private final boolean failFast;

	/**
	 * Hibernate validator specific flags to relax constraints on parameters.
	 */
	private final MethodValidationConfiguration methodValidationConfiguration;

	/**
	 * Metadata provider for XML configuration.
	 */
	private XmlMetaDataProvider xmlMetaDataProvider;

	/**
	 * Prior to the introduction of {@code ParameterNameProvider} all the bean meta data was static and could be
	 * cached for all created {@code Validator}s. {@code ParameterNameProvider} makes parts of the meta data and
	 * Bean Validation element descriptors dynamic, since depending of the used provider different parameter names
	 * could be used. To still have the metadata static we create a {@code BeanMetaDataManager} per parameter name
	 * provider. See also HV-659.
	 */
	private final Map<ExecutableParameterNameProvider, BeanMetaDataManager> beanMetaDataManagerMap;

	private final ValueExtractorManager valueExtractorManager;

	public ValidatorFactoryImpl(ConfigurationState configurationState) {
		ClassLoader externalClassLoader = getExternalClassLoader( configurationState );

		this.messageInterpolator = configurationState.getMessageInterpolator();
		this.traversableResolver = configurationState.getTraversableResolver();
		this.parameterNameProvider = new ExecutableParameterNameProvider( configurationState.getParameterNameProvider() );
		this.clockProvider = configurationState.getClockProvider();
		this.beanMetaDataManagerMap = Collections.synchronizedMap( new IdentityHashMap<ExecutableParameterNameProvider, BeanMetaDataManager>() );
		this.constraintHelper = new ConstraintHelper();
		this.typeResolutionHelper = new TypeResolutionHelper();
		this.executableHelper = new ExecutableHelper( typeResolutionHelper );

		boolean tmpFailFast = false;
		boolean tmpAllowOverridingMethodAlterParameterConstraint = false;
		boolean tmpAllowMultipleCascadedValidationOnReturnValues = false;
		boolean tmpAllowParallelMethodsDefineParameterConstraints = false;
		List<ValueExtractor<?>> tmpCascadedValueExtractors = new ArrayList<>( 5 );

		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateSpecificConfig = (ConfigurationImpl) configurationState;

			// check whether fail fast is programmatically enabled
			tmpFailFast = hibernateSpecificConfig.getFailFast();

			tmpAllowOverridingMethodAlterParameterConstraint =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowOverridingMethodAlterParameterConstraint();
			tmpAllowMultipleCascadedValidationOnReturnValues =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowMultipleCascadedValidationOnReturnValues();
			tmpAllowParallelMethodsDefineParameterConstraints =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowParallelMethodsDefineParameterConstraints();

			tmpCascadedValueExtractors = new ArrayList<>( hibernateSpecificConfig.getCascadedValueExtractors() );
		}

		this.valueExtractorManager = new ValueExtractorManager( tmpCascadedValueExtractors );

		// HV-302; don't load XmlMappingParser if not necessary
		if ( configurationState.getMappingStreams().isEmpty() ) {
			this.xmlMetaDataProvider = null;
		}
		else {
			this.xmlMetaDataProvider = new XmlMetaDataProvider(
					constraintHelper, typeResolutionHelper, parameterNameProvider, valueExtractorManager, configurationState.getMappingStreams(), externalClassLoader
			);
		}

		this.constraintMappings = Collections.unmodifiableSet(
				getConstraintMappings(
						configurationState,
						externalClassLoader
				)
		);

		registerCustomConstraintValidators( constraintMappings, constraintHelper );

		Map<String, String> properties = configurationState.getProperties();

		tmpFailFast = checkPropertiesForBoolean( properties, HibernateValidatorConfiguration.FAIL_FAST, tmpFailFast );
		this.failFast = tmpFailFast;

		this.methodValidationConfiguration = new MethodValidationConfiguration();

		tmpAllowOverridingMethodAlterParameterConstraint = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_PARAMETER_CONSTRAINT_OVERRIDE,
				tmpAllowOverridingMethodAlterParameterConstraint
		);
		this.methodValidationConfiguration.allowOverridingMethodAlterParameterConstraint(
				tmpAllowOverridingMethodAlterParameterConstraint
		);

		tmpAllowMultipleCascadedValidationOnReturnValues = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_MULTIPLE_CASCADED_VALIDATION_ON_RESULT,
				tmpAllowMultipleCascadedValidationOnReturnValues
		);
		this.methodValidationConfiguration.allowMultipleCascadedValidationOnReturnValues(
				tmpAllowMultipleCascadedValidationOnReturnValues
		);

		tmpAllowParallelMethodsDefineParameterConstraints = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_PARALLEL_METHODS_DEFINE_PARAMETER_CONSTRAINTS,
				tmpAllowParallelMethodsDefineParameterConstraints
		);
		this.methodValidationConfiguration.allowParallelMethodsDefineParameterConstraints(
				tmpAllowParallelMethodsDefineParameterConstraints
		);

		this.constraintValidatorManager = new ConstraintValidatorManager( configurationState.getConstraintValidatorFactory() );
	}

	private static ClassLoader getExternalClassLoader(ConfigurationState configurationState) {
		return ( configurationState instanceof ConfigurationImpl ) ? ( (ConfigurationImpl) configurationState ).getExternalClassLoader() : null;
	}

	private static Set<DefaultConstraintMapping> getConstraintMappings(ConfigurationState configurationState, ClassLoader externalClassLoader) {
		Set<DefaultConstraintMapping> constraintMappings = newHashSet();

		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateConfiguration = (ConfigurationImpl) configurationState;

			// programmatic config
			/* We add these first so that constraint mapping created through DefaultConstraintMappingBuilder will take
			 * these programmatically defined mappings into account when checking for constraint definition uniqueness
			 */
			constraintMappings.addAll( hibernateConfiguration.getProgrammaticMappings() );

			// service loader based config
			ConstraintMappingContributor serviceLoaderBasedContributor =
					hibernateConfiguration.getServiceLoaderBasedConstraintMappingContributor();
			DefaultConstraintMappingBuilder builder = new DefaultConstraintMappingBuilder( constraintMappings );
			serviceLoaderBasedContributor.createConstraintMappings( builder );
		}

		// XML-defined constraint mapping contributors
		List<ConstraintMappingContributor> contributors = getPropertyConfiguredConstraintMappingContributors( configurationState.getProperties(),
				externalClassLoader );

		for ( ConstraintMappingContributor contributor : contributors ) {
			DefaultConstraintMappingBuilder builder = new DefaultConstraintMappingBuilder( constraintMappings );
			contributor.createConstraintMappings( builder );
		}

		return constraintMappings;
	}

	@Override
	public Validator getValidator() {
		return createValidator(
				constraintValidatorManager.getDefaultConstraintValidatorFactory(),
				messageInterpolator,
				traversableResolver,
				parameterNameProvider,
				clockProvider,
				failFast,
				valueExtractorManager,
				methodValidationConfiguration
		);
	}

	@Override
	public MessageInterpolator getMessageInterpolator() {
		return messageInterpolator;
	}

	@Override
	public TraversableResolver getTraversableResolver() {
		return traversableResolver;
	}

	@Override
	public ConstraintValidatorFactory getConstraintValidatorFactory() {
		return constraintValidatorManager.getDefaultConstraintValidatorFactory();
	}

	@Override
	public ParameterNameProvider getParameterNameProvider() {
		return parameterNameProvider.getDelegate();
	}

	public ExecutableParameterNameProvider getExecutableParameterNameProvider() {
		return parameterNameProvider;
	}

	@Override
	public ClockProvider getClockProvider() {
		return clockProvider;
	}

	public boolean isFailFast() {
		return failFast;
	}

	ValueExtractorManager getValueExtractorManager() {
		return valueExtractorManager;
	}

	@Override
	public <T> T unwrap(Class<T> type) {
		//allow unwrapping into public super types
		if ( type.isAssignableFrom( HibernateValidatorFactory.class ) ) {
			return type.cast( this );
		}
		throw log.getTypeNotSupportedForUnwrappingException( type );
	}

	@Override
	public HibernateValidatorContext usingContext() {
		return new ValidatorContextImpl( this );
	}

	@Override
	public void close() {
		constraintValidatorManager.clear();
		for ( BeanMetaDataManager beanMetaDataManager : beanMetaDataManagerMap.values() ) {
			beanMetaDataManager.clear();
		}

		// this holds a reference to the provided external class-loader, thus freeing it to be on the safe side
		xmlMetaDataProvider = null;
	}

	Validator createValidator(ConstraintValidatorFactory constraintValidatorFactory,
			MessageInterpolator messageInterpolator,
			TraversableResolver traversableResolver,
			ExecutableParameterNameProvider parameterNameProvider,
			ClockProvider clockProvider,
			boolean failFast,
			ValueExtractorManager valueExtractorManager,
			MethodValidationConfiguration methodValidationConfiguration) {

		BeanMetaDataManager beanMetaDataManager;
		if ( !beanMetaDataManagerMap.containsKey( parameterNameProvider ) ) {
			beanMetaDataManager = new BeanMetaDataManager(
					constraintHelper,
					executableHelper,
					typeResolutionHelper,
					parameterNameProvider,
					valueExtractorManager,
					buildDataProviders( parameterNameProvider ),
					methodValidationConfiguration
			);
			beanMetaDataManagerMap.put( parameterNameProvider, beanMetaDataManager );
		}
		else {
			beanMetaDataManager = beanMetaDataManagerMap.get( parameterNameProvider );
		}

		return new ValidatorImpl(
				constraintValidatorFactory,
				messageInterpolator,
				traversableResolver,
				beanMetaDataManager,
				parameterNameProvider,
				clockProvider,
				valueExtractorManager,
				constraintValidatorManager,
				failFast
		);
	}

	private List<MetaDataProvider> buildDataProviders(ExecutableParameterNameProvider parameterNameProvider) {
		List<MetaDataProvider> metaDataProviders = newArrayList();
		if ( xmlMetaDataProvider != null ) {
			metaDataProviders.add( xmlMetaDataProvider );
		}

		if ( !constraintMappings.isEmpty() ) {
			metaDataProviders.add(
					new ProgrammaticMetaDataProvider(
							constraintHelper,
							typeResolutionHelper,
							parameterNameProvider,
							valueExtractorManager,
							constraintMappings
					)
			);
		}
		return metaDataProviders;
	}

	private boolean checkPropertiesForBoolean(Map<String, String> properties, String propertyKey, boolean programmaticValue) {
		boolean value = programmaticValue;
		String propertyStringValue = properties.get( propertyKey );
		if ( propertyStringValue != null ) {
			boolean configurationValue = Boolean.valueOf( propertyStringValue );
			// throw an exception if the programmatic value is true and it overrides a false configured value
			if ( programmaticValue && !configurationValue ) {
				throw log.getInconsistentFailFastConfigurationException();
			}
			value = configurationValue;
		}
		return value;
	}

	/**
	 * Returns a list with {@link ConstraintMappingContributor}s configured via the
	 * {@link HibernateValidatorConfiguration#CONSTRAINT_MAPPING_CONTRIBUTORS} property.
	 *
	 * @param properties the properties used to bootstrap the factory
	 *
	 * @return a list with property-configured {@link ContraintMappingContributor}s; May be empty but never {@code null}
	 */
	private static List<ConstraintMappingContributor> getPropertyConfiguredConstraintMappingContributors(
			Map<String, String> properties, ClassLoader externalClassLoader) {
		String propertyValue = properties.get( HibernateValidatorConfiguration.CONSTRAINT_MAPPING_CONTRIBUTORS );

		if ( StringHelper.isNullOrEmptyString( propertyValue ) ) {
			return Collections.emptyList();
		}

		String[] contributorNames = propertyValue.toString().split( "," );
		List<ConstraintMappingContributor> contributors = newArrayList( contributorNames.length );

		for ( String contributorName : contributorNames ) {
			@SuppressWarnings("unchecked")
			Class<? extends ConstraintMappingContributor> contributorType = (Class<? extends ConstraintMappingContributor>) run(
					LoadClass.action( contributorName, externalClassLoader ) );
			contributors.add( run( NewInstance.action( contributorType, "constraint mapping contributor class" ) ) );
		}

		return contributors;
	}

	private static void registerCustomConstraintValidators(Set<DefaultConstraintMapping> constraintMappings,
			ConstraintHelper constraintHelper) {
		Set<Class<?>> definedConstraints = newHashSet();
		for ( DefaultConstraintMapping constraintMapping : constraintMappings ) {
			for ( ConstraintDefinitionContribution<?> contribution : constraintMapping.getConstraintDefinitionContributions() ) {
				processConstraintDefinitionContribution( contribution, constraintHelper, definedConstraints );
			}
		}
	}

	private static <A extends Annotation> void processConstraintDefinitionContribution(
			ConstraintDefinitionContribution<A> constraintDefinitionContribution, ConstraintHelper constraintHelper,
			Set<Class<?>> definedConstraints) {
		Class<A> constraintType = constraintDefinitionContribution.getConstraintType();
		if ( definedConstraints.contains( constraintType ) ) {
			throw log.getConstraintHasAlreadyBeenConfiguredViaProgrammaticApiException( constraintType );
		}
		definedConstraints.add( constraintType );
		constraintHelper.putValidatorDescriptors(
				constraintType,
				constraintDefinitionContribution.getValidatorDescriptors(),
				constraintDefinitionContribution.includeExisting()
		);
	}

	/**
	 * Runs the given privileged action, using a privileged block if required.
	 * <p>
	 * <b>NOTE:</b> This must never be changed into a publicly available method to avoid execution of arbitrary
	 * privileged actions within HV's protection domain.
	 */
	private static <T> T run(PrivilegedAction<T> action) {
		return System.getSecurityManager() != null ? AccessController.doPrivileged( action ) : action.run();
	}

	/**
	 * The one and only {@link ConstraintMappingContributor.ConstraintMappingBuilder} implementation.
	 */
	private static class DefaultConstraintMappingBuilder
			implements ConstraintMappingContributor.ConstraintMappingBuilder {
		private final Set<DefaultConstraintMapping> mappings;

		public DefaultConstraintMappingBuilder(Set<DefaultConstraintMapping> mappings) {
			super();
			this.mappings = mappings;
		}

		@Override
		public ConstraintMapping addConstraintMapping() {
			DefaultConstraintMapping mapping = new DefaultConstraintMapping();
			mappings.add( mapping );
			return mapping;
		}
	}
}
